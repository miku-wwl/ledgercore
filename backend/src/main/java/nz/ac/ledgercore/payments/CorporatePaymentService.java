package nz.ac.ledgercore.payments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nz.ac.ledgercore.accounts.AccountOwner;
import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.api.ApiNotFoundException;
import nz.ac.ledgercore.TransactionRollbackMetrics;
import nz.ac.ledgercore.events.DurableEventStore;
import nz.ac.ledgercore.ledger.InsufficientLedgerBalanceException;
import nz.ac.ledgercore.ledger.LedgerWriter;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationService;
import nz.ac.ledgercore.organizations.OrganizationRole;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CorporatePaymentService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountService accountService;
    private final OrganizationAuthorizationService authorizationService;
    private final LedgerWriter ledgerWriter;
    private final DurableEventStore eventStore;
    private final MeterRegistry meterRegistry;
    private final TransactionRollbackMetrics rollbackMetrics;

    public CorporatePaymentService(JdbcTemplate jdbcTemplate, AccountService accountService,
            OrganizationAuthorizationService authorizationService, LedgerWriter ledgerWriter,
            DurableEventStore eventStore, MeterRegistry meterRegistry, TransactionRollbackMetrics rollbackMetrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountService = accountService;
        this.authorizationService = authorizationService;
        this.ledgerWriter = ledgerWriter;
        this.eventStore = eventStore;
        this.meterRegistry = meterRegistry;
        this.rollbackMetrics = rollbackMetrics;
    }

    @Transactional
    public PaymentView createDraft(UUID actorCustomerId, UUID organizationId, NewPayment request) {
        authorizationService.requireCanMakePayment(actorCustomerId, organizationId);
        AccountOwner sourceOwner = accountService.ownerOf(request.sourceAccountId())
                .orElseThrow(() -> new ApiNotFoundException("Corporate source account was not found."));
        if (!organizationId.equals(sourceOwner.organizationId())) {
            throw new ApiNotFoundException("Corporate source account was not found.");
        }
        accountService.assertOpen(request.sourceAccountId());
        String beneficiaryName = normalized(request.beneficiaryName(), 120, "Beneficiary name");
        String beneficiaryAccount = normalized(request.beneficiaryAccountReference(), 80, "Beneficiary account reference");
        String routingCode = normalized(request.beneficiaryRoutingCode(), 32, "Beneficiary routing code");
        Money amount = Money.parsePositiveNzd(request.amount());
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO corporate_payment_intents (id, operation_id, organization_id, source_account_id,
                    created_by_customer_id, beneficiary_name, beneficiary_account_reference,
                    beneficiary_routing_code, amount_minor, currency)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'NZD')
                """, paymentId, paymentId, organizationId, request.sourceAccountId(), actorCustomerId,
                beneficiaryName, beneficiaryAccount, routingCode, amount.minorUnits());
        eventStore.recordAudit(actorCustomerId, "CORPORATE_PAYMENT_CREATED", "CORPORATE_PAYMENT", paymentId,
                organizationId, Map.of("amountMinor", amount.minorUnits(), "currency", "NZD"));
        eventStore.enqueue("corporate.payment.created:" + paymentId, "corporate.payment.created",
                "CORPORATE_PAYMENT", paymentId, actorCustomerId, organizationId,
                Map.of("paymentId", paymentId.toString(), "amountMinor", amount.minorUnits(), "currency", "NZD"));
        return viewFor(actorCustomerId, paymentId);
    }

    @Transactional
    public PaymentView submit(UUID actorCustomerId, UUID paymentId) {
        PaymentRow payment = lockPayment(paymentId);
        authorizationService.requireCanMakePayment(actorCustomerId, payment.organizationId());
        if (!actorCustomerId.equals(payment.createdByCustomerId())) {
            throw new CorporatePaymentException("PAYMENT_MAKER_MISMATCH",
                    "Only the payment maker who created this draft can submit it.", HttpStatus.FORBIDDEN);
        }
        if (!"DRAFT".equals(payment.status())) {
            if ("SUBMITTED".equals(payment.status()) && actorCustomerId.equals(payment.submittedByCustomerId())) {
                return viewFor(actorCustomerId, paymentId);
            }
            throw conflict("PAYMENT_NOT_DRAFT", "Only a draft corporate payment can be submitted.");
        }
        String digest = CorporatePaymentDigest.calculate(payment);
        int updated = jdbcTemplate.update("""
                UPDATE corporate_payment_intents
                SET status = 'SUBMITTED', submitted_by_customer_id = ?, submitted_at = clock_timestamp(),
                    intent_digest = ?, updated_at = clock_timestamp()
                WHERE id = ? AND status = 'DRAFT' AND version = ?
                """, actorCustomerId, digest, paymentId, payment.version());
        if (updated != 1) {
            throw conflict("PAYMENT_STATE_CHANGED", "The payment changed before submission completed.");
        }
        eventStore.recordAudit(actorCustomerId, "CORPORATE_PAYMENT_SUBMITTED", "CORPORATE_PAYMENT", paymentId,
                payment.organizationId(), Map.of("version", payment.version(), "digest", digest));
        eventStore.enqueue("corporate.payment.submitted:" + paymentId, "corporate.payment.submitted",
                "CORPORATE_PAYMENT", paymentId, actorCustomerId, payment.organizationId(),
                Map.of("version", payment.version(), "digest", digest));
        return viewFor(actorCustomerId, paymentId);
    }

    @Transactional
    public PaymentView approve(UUID actorCustomerId, UUID paymentId) {
        rollbackMetrics.observe("corporate_payment_approval");
        PaymentRow payment = lockPayment(paymentId);
        authorizationService.requireCanApprovePayment(actorCustomerId, payment.organizationId(),
                payment.createdByCustomerId());
        if (actorCustomerId.equals(payment.submittedByCustomerId())) {
            throw new CorporatePaymentException("PAYMENT_MAKER_CHECKER", "A payment submitter cannot approve their own intent.",
                    HttpStatus.FORBIDDEN);
        }
        if (actorCustomerId.equals(payment.approvedByCustomerId()) && payment.approvedDigest() != null) {
            return viewFor(actorCustomerId, paymentId);
        }
        if (!"SUBMITTED".equals(payment.status())) {
            throw conflict("PAYMENT_NOT_SUBMITTED", "Only a submitted corporate payment can be approved.");
        }
        String actualDigest = CorporatePaymentDigest.calculate(payment);
        if (!actualDigest.equals(payment.intentDigest())) {
            throw conflict("PAYMENT_CONTENT_CHANGED", "Submitted payment content does not match its saved digest.");
        }
        int updated = jdbcTemplate.update("""
                UPDATE corporate_payment_intents
                SET status = 'APPROVED', approved_by_customer_id = ?, approved_at = clock_timestamp(),
                    approved_version = version, approved_digest = ?, updated_at = clock_timestamp()
                WHERE id = ? AND status = 'SUBMITTED' AND intent_digest = ? AND version = ?
                """, actorCustomerId, actualDigest, paymentId, actualDigest, payment.version());
        if (updated != 1) {
            throw conflict("PAYMENT_STATE_CHANGED", "The payment changed before approval completed.");
        }
        try {
            UUID bookingJournalEntryId = ledgerWriter.bookApprovedCorporatePayment(paymentId);
            jdbcTemplate.update("""
                    INSERT INTO clearing_instructions (operation_id, payment_intent_id, intent_digest)
                    VALUES (?, ?, ?)
                    """, payment.operationId(), paymentId, actualDigest);
            eventStore.recordAudit(actorCustomerId, "CORPORATE_PAYMENT_APPROVED_AND_BOOKED", "CORPORATE_PAYMENT",
                    paymentId, payment.organizationId(), Map.of("version", payment.version(), "digest", actualDigest,
                    "bookingJournalEntryId", bookingJournalEntryId.toString()));
            eventStore.enqueue("corporate.payment.approved:" + paymentId, "corporate.payment.approved",
                    "CORPORATE_PAYMENT", paymentId, actorCustomerId, payment.organizationId(),
                    Map.of("paymentId", paymentId.toString(), "version", payment.version(), "digest", actualDigest));
            eventStore.enqueue("clearing.instruction.ready:" + payment.operationId(), "clearing.instruction.ready",
                    "CLEARING_INSTRUCTION", payment.operationId(), actorCustomerId, payment.organizationId(),
                    Map.of("operationId", payment.operationId().toString(), "paymentId", paymentId.toString(),
                            "digest", actualDigest));
        }
        catch (InsufficientLedgerBalanceException exception) {
            throw new CorporatePaymentException("INSUFFICIENT_FUNDS",
                    "The corporate account does not have enough available funds.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Double approvalDelaySeconds = jdbcTemplate.queryForObject("""
                SELECT extract(epoch FROM approved_at - submitted_at)
                FROM corporate_payment_intents WHERE id = ?
                """, Double.class, paymentId);
        if (approvalDelaySeconds != null && approvalDelaySeconds >= 0) {
            meterRegistry.timer("ledgercore.corporate_payment.approval.delay", "outcome", "approved")
                    .record(java.time.Duration.ofNanos((long) (approvalDelaySeconds * 1_000_000_000L)));
        }
        return viewFor(actorCustomerId, paymentId);
    }

    @Transactional
    public PaymentView reject(UUID actorCustomerId, UUID paymentId, String reason) {
        PaymentRow payment = lockPayment(paymentId);
        authorizationService.requireCanApprovePayment(actorCustomerId, payment.organizationId(),
                payment.createdByCustomerId());
        if (actorCustomerId.equals(payment.submittedByCustomerId())) {
            throw new CorporatePaymentException("PAYMENT_MAKER_CHECKER", "A payment submitter cannot decide their own intent.",
                    HttpStatus.FORBIDDEN);
        }
        String normalizedReason = normalized(reason, 300, "Rejection reason");
        if ("REJECTED".equals(payment.status()) && actorCustomerId.equals(payment.rejectedByCustomerId())
                && normalizedReason.equals(payment.rejectionReason())) {
            return viewFor(actorCustomerId, paymentId);
        }
        if (!"SUBMITTED".equals(payment.status())) {
            throw conflict("PAYMENT_NOT_SUBMITTED", "Only a submitted corporate payment can be rejected.");
        }
        int updated = jdbcTemplate.update("""
                UPDATE corporate_payment_intents
                SET status = 'REJECTED', rejected_by_customer_id = ?, rejected_at = clock_timestamp(),
                    rejection_reason = ?, completed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ? AND status = 'SUBMITTED' AND intent_digest = ?
                """, actorCustomerId, normalizedReason, paymentId, payment.intentDigest());
        if (updated != 1) {
            throw conflict("PAYMENT_STATE_CHANGED", "The payment changed before rejection completed.");
        }
        eventStore.recordAudit(actorCustomerId, "CORPORATE_PAYMENT_REJECTED", "CORPORATE_PAYMENT", paymentId,
                payment.organizationId(), Map.of("reason", normalizedReason));
        eventStore.enqueue("corporate.payment.rejected:" + paymentId, "corporate.payment.rejected",
                "CORPORATE_PAYMENT", paymentId, actorCustomerId, payment.organizationId(),
                Map.of("reason", normalizedReason));
        return viewFor(actorCustomerId, paymentId);
    }

    @Transactional(readOnly = true)
    public PaymentPage list(UUID actorCustomerId, UUID organizationId, int page, int size) {
        authorizationService.requireMember(actorCustomerId, organizationId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        Integer total = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM corporate_payment_intents WHERE organization_id = ?
                """, Integer.class, organizationId);
        List<UUID> ids = jdbcTemplate.query("""
                SELECT id FROM corporate_payment_intents WHERE organization_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, (rs, row) -> rs.getObject(1, UUID.class), organizationId, safeSize, safePage * safeSize);
        return new PaymentPage(ids.stream().map(id -> viewFor(actorCustomerId, id)).toList(), safePage, safeSize,
                total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public PaymentView get(UUID actorCustomerId, UUID paymentId) {
        PaymentRow payment = findPayment(paymentId);
        authorizationService.requireMember(actorCustomerId, payment.organizationId());
        return viewFor(actorCustomerId, payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentView> recent(UUID actorCustomerId, int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<UUID> ids = jdbcTemplate.query("""
                SELECT p.id FROM corporate_payment_intents p
                JOIN organization_memberships m ON m.organization_id = p.organization_id
                    AND m.customer_id = ? AND m.status = 'ACTIVE'
                JOIN organizations o ON o.id = p.organization_id AND o.status = 'ACTIVE'
                ORDER BY p.created_at DESC, p.id DESC LIMIT ?
                """, (rs, row) -> rs.getObject(1, UUID.class), actorCustomerId, safeLimit);
        return ids.stream().map(id -> viewFor(actorCustomerId, id)).toList();
    }

    private PaymentRow lockPayment(UUID paymentId) {
        List<PaymentRow> rows = jdbcTemplate.query(paymentSql("FOR UPDATE"), this::paymentRow, paymentId);
        return rows.stream().findFirst().orElseThrow(() -> new ApiNotFoundException("Corporate payment was not found."));
    }

    private PaymentRow findPayment(UUID paymentId) {
        List<PaymentRow> rows = jdbcTemplate.query(paymentSql(""), this::paymentRow, paymentId);
        return rows.stream().findFirst().orElseThrow(() -> new ApiNotFoundException("Corporate payment was not found."));
    }

    private PaymentView viewFor(UUID actorCustomerId, UUID paymentId) {
        return viewFor(actorCustomerId, findPayment(paymentId));
    }

    private PaymentView viewFor(UUID actorCustomerId, PaymentRow payment) {
        List<OrganizationRole> roles = authorizationService.rolesFor(actorCustomerId, payment.organizationId());
        boolean canViewBeneficiaryBankInstructions = roles.contains(OrganizationRole.PAYMENT_MAKER)
                || roles.contains(OrganizationRole.PAYMENT_APPROVER);
        return new PaymentView(payment.id(), payment.operationId(), payment.organizationId(), payment.sourceAccountId(),
                payment.createdByCustomerId(), payment.submittedByCustomerId(), payment.approvedByCustomerId(),
                payment.rejectedByCustomerId(), payment.beneficiaryName(),
                canViewBeneficiaryBankInstructions ? payment.beneficiaryAccountReference() : "[REDACTED]",
                canViewBeneficiaryBankInstructions ? payment.beneficiaryRoutingCode() : "[REDACTED]",
                new Money(payment.amountMinor()).toNzdString(), payment.currency(),
                payment.version(), payment.intentDigest(), payment.approvedDigest(), payment.status(),
                payment.externalStatus(), payment.bookingJournalEntryId(), payment.settlementJournalEntryId(),
                payment.reversalJournalEntryId(), payment.rejectionReason(), payment.createdAt(), payment.submittedAt(),
                payment.approvedAt(), payment.completedAt());
    }

    private String paymentSql(String lock) {
        return """
                SELECT id, operation_id, organization_id, source_account_id, created_by_customer_id,
                       submitted_by_customer_id, approved_by_customer_id, rejected_by_customer_id,
                       beneficiary_name, beneficiary_account_reference, beneficiary_routing_code, amount_minor,
                       currency, version, intent_digest, approved_digest, status, external_status,
                       booking_journal_entry_id, settlement_journal_entry_id, reversal_journal_entry_id,
                       rejection_reason, created_at, submitted_at, approved_at, completed_at
                FROM corporate_payment_intents WHERE id = ?
                """ + lock;
    }

    private PaymentRow paymentRow(ResultSet rs, int row) throws SQLException {
        return new PaymentRow(rs.getObject("id", UUID.class), rs.getObject("operation_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getObject("source_account_id", UUID.class),
                rs.getObject("created_by_customer_id", UUID.class), rs.getObject("submitted_by_customer_id", UUID.class),
                rs.getObject("approved_by_customer_id", UUID.class), rs.getObject("rejected_by_customer_id", UUID.class),
                rs.getString("beneficiary_name"), rs.getString("beneficiary_account_reference"),
                rs.getString("beneficiary_routing_code"), rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getInt("version"), rs.getString("intent_digest"), rs.getString("approved_digest"),
                rs.getString("status"), rs.getString("external_status"),
                rs.getObject("booking_journal_entry_id", UUID.class),
                rs.getObject("settlement_journal_entry_id", UUID.class),
                rs.getObject("reversal_journal_entry_id", UUID.class), rs.getString("rejection_reason"),
                rs.getTimestamp("created_at").toInstant(), instant(rs, "submitted_at"), instant(rs, "approved_at"),
                instant(rs, "completed_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant();
    }

    private String normalized(String value, int maxLength, String label) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank() || result.length() > maxLength) {
            throw new IllegalArgumentException(label + " must contain 1 to " + maxLength + " characters.");
        }
        return result;
    }

    private CorporatePaymentException conflict(String code, String message) {
        return new CorporatePaymentException(code, message, HttpStatus.CONFLICT);
    }

    public record NewPayment(UUID sourceAccountId, String beneficiaryName, String beneficiaryAccountReference,
            String beneficiaryRoutingCode, String amount) {
    }

    public record PaymentView(UUID id, UUID operationId, UUID organizationId, UUID sourceAccountId,
            UUID createdByCustomerId, UUID submittedByCustomerId, UUID approvedByCustomerId, UUID rejectedByCustomerId,
            String beneficiaryName, String beneficiaryAccountReference, String beneficiaryRoutingCode, String amount,
            String currency, int version, String intentDigest, String approvedDigest, String status,
            String externalStatus, UUID bookingJournalEntryId, UUID settlementJournalEntryId,
            UUID reversalJournalEntryId, String rejectionReason, Instant createdAt, Instant submittedAt,
            Instant approvedAt, Instant completedAt) {
    }

    public record PaymentPage(List<PaymentView> items, int page, int size, int total) {
    }

    record PaymentRow(UUID id, UUID operationId, UUID organizationId, UUID sourceAccountId,
            UUID createdByCustomerId, UUID submittedByCustomerId, UUID approvedByCustomerId,
            UUID rejectedByCustomerId, String beneficiaryName, String beneficiaryAccountReference,
            String beneficiaryRoutingCode, long amountMinor, String currency, int version, String intentDigest,
            String approvedDigest, String status, String externalStatus, UUID bookingJournalEntryId,
            UUID settlementJournalEntryId, UUID reversalJournalEntryId, String rejectionReason, Instant createdAt,
            Instant submittedAt, Instant approvedAt, Instant completedAt) {
    }
}
