package nz.ac.ledgercore.payroll;

import static nz.ac.ledgercore.payroll.PayrollViews.BatchView;
import static nz.ac.ledgercore.payroll.PayrollViews.ItemView;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import nz.ac.ledgercore.accounts.AccountOwner;
import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.api.ApiNotFoundException;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationException;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollService {

    private static final int MAX_ITEMS_PER_IMPORT = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final AccountService accountService;
    private final OrganizationAuthorizationService authorizationService;

    public PayrollService(JdbcTemplate jdbcTemplate, AccountService accountService,
            OrganizationAuthorizationService authorizationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountService = accountService;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public BatchView createDraft(UUID actorCustomerId, UUID organizationId, String name, UUID payerAccountId) {
        authorizationService.requireCanPreparePayroll(actorCustomerId, organizationId);
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank() || normalizedName.length() > 120) {
            throw new IllegalArgumentException("Payroll batch name must contain 1 to 120 characters.");
        }
        AccountOwner owner = accountService.ownerOf(payerAccountId)
                .orElseThrow(() -> new ApiNotFoundException("Payroll payer account was not found."));
        if (owner.organizationId() == null || !owner.organizationId().equals(organizationId)) {
            throw new ApiNotFoundException("Payroll payer account was not found.");
        }
        accountService.assertOpen(payerAccountId);

        UUID batchId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payroll_batches (id, organization_id, payer_account_id, name, created_by_customer_id)
                VALUES (?, ?, ?, ?, ?)
                """, batchId, organizationId, payerAccountId, normalizedName, actorCustomerId);
        return viewFor(batchId);
    }

    @Transactional
    public BatchView addItems(UUID actorCustomerId, UUID batchId, List<NewPayrollItem> additions) {
        BatchRow batch = lockBatch(batchId);
        authorizationService.requireCanPreparePayroll(actorCustomerId, batch.organizationId());
        requireDraftCreator(actorCustomerId, batch);
        requireDraft(batch);
        if (additions == null || additions.isEmpty() || additions.size() > MAX_ITEMS_PER_IMPORT) {
            throw new IllegalArgumentException("Add between 1 and 1,000 payroll items per request.");
        }

        Set<String> references = new HashSet<>(jdbcTemplate.query("""
                SELECT lower(employee_reference) FROM payroll_items WHERE batch_id = ?
                """, (rs, row) -> rs.getString(1), batchId));
        int sequence = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(sequence_no), 0) FROM payroll_items WHERE batch_id = ?
                """, Integer.class, batchId);
        long currentTotal = totalMinorFor(batchId);
        List<ValidatedItem> validated = new ArrayList<>(additions.size());

        for (NewPayrollItem addition : additions) {
            String reference = addition.employeeReference() == null ? "" : addition.employeeReference().trim();
            String employeeName = addition.employeeName() == null ? "" : addition.employeeName().trim();
            if (reference.isBlank() || reference.length() > 80 || employeeName.isBlank()
                    || employeeName.length() > 120 || addition.payeeAccountId() == null) {
                throw new IllegalArgumentException("Each item requires an employee reference, name, and payee account.");
            }
            if (!references.add(reference.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Employee references must be unique within a payroll batch.");
            }
            Money amount = Money.parsePositiveNzd(addition.amount());
            try {
                currentTotal = Math.addExact(currentTotal, amount.minorUnits());
            }
            catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Payroll batch total exceeds the supported NZD range.", exception);
            }
            AccountOwner payeeOwner = accountService.ownerOf(addition.payeeAccountId())
                    .orElseThrow(() -> new ApiNotFoundException("A payroll payee account was not found."));
            if (payeeOwner.customerId() == null) {
                throw new IllegalArgumentException("Payroll payees must use personal customer accounts.");
            }
            accountService.assertOpen(addition.payeeAccountId());
            validated.add(new ValidatedItem(++sequence, reference, employeeName, addition.payeeAccountId(),
                    payeeOwner.customerId(), amount.minorUnits()));
        }

        for (ValidatedItem item : validated) {
            UUID itemId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO payroll_items
                        (id, batch_id, sequence_no, employee_reference, employee_name, payee_account_id,
                         payee_owner_customer_id, amount_minor, currency)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'NZD')
                    """, itemId, batchId, item.sequenceNumber(), item.employeeReference(), item.employeeName(),
                    item.payeeAccountId(), item.payeeOwnerCustomerId(), item.amountMinor());
            jdbcTemplate.update("INSERT INTO payroll_item_results (item_id) VALUES (?)", itemId);
        }
        return viewFor(batchId);
    }

    @Transactional
    public BatchView submit(UUID actorCustomerId, UUID batchId) {
        BatchRow batch = lockBatch(batchId);
        authorizationService.requireCanPreparePayroll(actorCustomerId, batch.organizationId());
        requireDraftCreator(actorCustomerId, batch);
        if (batch.status() != PayrollBatchStatus.DRAFT) {
            if (batch.submittedByCustomerId() != null && batch.submittedByCustomerId().equals(actorCustomerId)) {
                return viewFor(batchId);
            }
            throw conflict("BATCH_ALREADY_SUBMITTED", "Only a draft batch can be submitted.");
        }

        List<PayrollContentDigest.Item> items = digestItems(batchId);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A payroll batch must contain at least one item before submission.");
        }
        String digest = PayrollContentDigest.calculate(batch.id(), batch.organizationId(), batch.payerAccountId(),
                batch.name(), items);
        int updated = jdbcTemplate.update("""
                UPDATE payroll_batches
                SET status = 'SUBMITTED', submitted_by_customer_id = ?, submitted_at = clock_timestamp(),
                    content_digest = ?, updated_at = clock_timestamp()
                WHERE id = ? AND status = 'DRAFT'
                """, actorCustomerId, digest, batchId);
        if (updated != 1) {
            throw conflict("BATCH_STATE_CHANGED", "The payroll batch changed before submission completed.");
        }
        return viewFor(batchId);
    }

    @Transactional
    public BatchView approve(UUID actorCustomerId, UUID batchId) {
        BatchRow batch = lockBatch(batchId);
        if (batch.submittedByCustomerId() == null) {
            throw conflict("BATCH_NOT_SUBMITTED", "Only a submitted payroll batch can be approved.");
        }
        authorizationService.requireCanApprovePayroll(actorCustomerId, batch.organizationId(),
                batch.submittedByCustomerId());
        if (batch.status() != PayrollBatchStatus.SUBMITTED) {
            if (batch.status() == PayrollBatchStatus.APPROVED && actorCustomerId.equals(batch.approvedByCustomerId())) {
                return viewFor(batchId);
            }
            throw conflict("BATCH_NOT_SUBMITTED", "Only a submitted payroll batch can be approved.");
        }
        String actualDigest = PayrollContentDigest.calculate(batch.id(), batch.organizationId(), batch.payerAccountId(),
                batch.name(), digestItems(batchId));
        if (!actualDigest.equals(batch.contentDigest())) {
            throw conflict("PAYROLL_CONTENT_CHANGED", "Submitted payroll content does not match its saved digest.");
        }
        int updated = jdbcTemplate.update("""
                UPDATE payroll_batches
                SET status = 'APPROVED', approved_by_customer_id = ?, approved_at = clock_timestamp(),
                    approved_content_digest = ?, updated_at = clock_timestamp()
                WHERE id = ? AND status = 'SUBMITTED' AND content_digest = ?
                """, actorCustomerId, actualDigest, batchId, actualDigest);
        if (updated != 1) {
            throw conflict("BATCH_STATE_CHANGED", "The payroll batch changed before approval completed.");
        }
        return viewFor(batchId);
    }

    @Transactional
    public BatchView reject(UUID actorCustomerId, UUID batchId, String reason) {
        BatchRow batch = lockBatch(batchId);
        if (batch.submittedByCustomerId() == null) {
            throw conflict("BATCH_NOT_SUBMITTED", "Only a submitted payroll batch can be rejected.");
        }
        authorizationService.requireCanApprovePayroll(actorCustomerId, batch.organizationId(),
                batch.submittedByCustomerId());
        if (batch.status() != PayrollBatchStatus.SUBMITTED) {
            if (batch.status() == PayrollBatchStatus.REJECTED && actorCustomerId.equals(batch.rejectedByCustomerId())) {
                return viewFor(batchId);
            }
            throw conflict("BATCH_NOT_SUBMITTED", "Only a submitted payroll batch can be rejected.");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isBlank() || normalizedReason.length() > 500) {
            throw new IllegalArgumentException("A rejection reason of 1 to 500 characters is required.");
        }
        String actualDigest = PayrollContentDigest.calculate(batch.id(), batch.organizationId(), batch.payerAccountId(),
                batch.name(), digestItems(batchId));
        if (!actualDigest.equals(batch.contentDigest())) {
            throw conflict("PAYROLL_CONTENT_CHANGED", "Submitted payroll content does not match its saved digest.");
        }
        int updated = jdbcTemplate.update("""
                UPDATE payroll_batches
                SET status = 'REJECTED', rejected_by_customer_id = ?, rejected_at = clock_timestamp(),
                    rejection_reason = ?, updated_at = clock_timestamp()
                WHERE id = ? AND status = 'SUBMITTED' AND content_digest = ?
                """, actorCustomerId, normalizedReason, batchId, actualDigest);
        if (updated != 1) {
            throw conflict("BATCH_STATE_CHANGED", "The payroll batch changed before rejection completed.");
        }
        return viewFor(batchId);
    }

    @Transactional(readOnly = true)
    public BatchView get(UUID actorCustomerId, UUID batchId) {
        BatchRow batch = findBatch(batchId).orElseThrow(() -> new ApiNotFoundException("Payroll batch was not found."));
        authorizationService.requireMember(actorCustomerId, batch.organizationId());
        return viewFor(batchId);
    }

    @Transactional(readOnly = true)
    public List<BatchView> listForOrganization(UUID actorCustomerId, UUID organizationId) {
        authorizationService.requireMember(actorCustomerId, organizationId);
        List<UUID> ids = jdbcTemplate.query("""
                SELECT id FROM payroll_batches WHERE organization_id = ? ORDER BY created_at DESC, id DESC
                """, (rs, row) -> rs.getObject(1, UUID.class), organizationId);
        return ids.stream().map(this::viewFor).toList();
    }

    @Transactional(readOnly = true)
    public List<ItemView> items(UUID actorCustomerId, UUID batchId) {
        BatchRow batch = findBatch(batchId).orElseThrow(() -> new ApiNotFoundException("Payroll batch was not found."));
        authorizationService.requireMember(actorCustomerId, batch.organizationId());
        return jdbcTemplate.query("""
                SELECT i.id, i.sequence_no, i.employee_reference, i.employee_name, i.payee_account_id,
                       i.amount_minor, r.status, r.attempt_count, r.transfer_id, r.error_code, r.error_message,
                       r.completed_at
                FROM payroll_items i
                JOIN payroll_item_results r ON r.item_id = i.id
                WHERE i.batch_id = ? ORDER BY i.sequence_no
                """, this::itemView, batchId);
    }

    @Transactional
    public BatchRow lockForExecution(UUID batchId) {
        return lockBatch(batchId);
    }

    @Transactional
    public BatchView startProcessing(UUID batchId) {
        BatchRow batch = lockBatch(batchId);
        if (batch.status() == PayrollBatchStatus.COMPLETED || batch.status() == PayrollBatchStatus.PARTIALLY_COMPLETED
                || batch.status() == PayrollBatchStatus.FAILED) {
            return viewFor(batchId);
        }
        if (batch.status() == PayrollBatchStatus.APPROVED) {
            String actualDigest = PayrollContentDigest.calculate(batch.id(), batch.organizationId(),
                    batch.payerAccountId(), batch.name(), digestItems(batchId));
            if (!actualDigest.equals(batch.approvedContentDigest())) {
                throw conflict("PAYROLL_CONTENT_CHANGED", "Approved payroll content no longer matches its digest.");
            }
            jdbcTemplate.update("""
                    UPDATE payroll_batches
                    SET status = 'PROCESSING', started_at = clock_timestamp(), updated_at = clock_timestamp()
                    WHERE id = ? AND status = 'APPROVED'
                    """, batchId);
        }
        else if (batch.status() != PayrollBatchStatus.PROCESSING) {
            throw conflict("BATCH_NOT_APPROVED", "Only an approved or already processing batch can execute.");
        }
        return viewFor(batchId);
    }

    @Transactional
    public BatchView finishIfReady(UUID batchId) {
        BatchRow batch = lockBatch(batchId);
        if (batch.status() != PayrollBatchStatus.PROCESSING) {
            return viewFor(batchId);
        }
        Counts counts = countsFor(batchId);
        if (counts.pendingCount() > 0) {
            return viewFor(batchId);
        }
        String terminalStatus = counts.failedCount() == 0 ? "COMPLETED"
                : counts.succeededCount() == 0 ? "FAILED" : "PARTIALLY_COMPLETED";
        jdbcTemplate.update("""
                UPDATE payroll_batches
                SET status = ?, completed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE id = ? AND status = 'PROCESSING'
                """, terminalStatus, batchId);
        return viewFor(batchId);
    }

    @Transactional
    public void markItemFailed(UUID itemId, String errorCode, String errorMessage) {
        int updated = jdbcTemplate.update("""
                UPDATE payroll_item_results
                SET status = 'FAILED', attempt_count = attempt_count + 1, error_code = ?, error_message = ?,
                    completed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE item_id = ? AND status = 'PENDING'
                """, errorCode, errorMessage, itemId);
        if (updated != 1) {
            // A concurrently executing worker may already have committed this item.
            Integer pending = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM payroll_item_results WHERE item_id = ? AND status = 'PENDING'
                    """, Integer.class, itemId);
            if (pending == null || pending != 0) {
                throw new IllegalStateException("Payroll item result could not be marked failed.");
            }
        }
    }

    @Transactional(readOnly = true)
    public Optional<BatchRow> findBatch(UUID batchId) {
        return jdbcTemplate.query("""
                SELECT id, organization_id, payer_account_id, name, status, created_by_customer_id,
                       submitted_by_customer_id, approved_by_customer_id, rejected_by_customer_id,
                       content_digest, approved_content_digest
                FROM payroll_batches WHERE id = ?
                """, (rs, row) -> batchRow(rs), batchId).stream().findFirst();
    }

    private BatchRow lockBatch(UUID batchId) {
        return jdbcTemplate.query("""
                SELECT id, organization_id, payer_account_id, name, status, created_by_customer_id,
                       submitted_by_customer_id, approved_by_customer_id, rejected_by_customer_id,
                       content_digest, approved_content_digest
                FROM payroll_batches WHERE id = ? FOR UPDATE
                """, (rs, row) -> batchRow(rs), batchId).stream().findFirst()
                .orElseThrow(() -> new ApiNotFoundException("Payroll batch was not found."));
    }

    private BatchRow batchRow(ResultSet rs) throws SQLException {
        return new BatchRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("payer_account_id", UUID.class), rs.getString("name"),
                PayrollBatchStatus.valueOf(rs.getString("status")),
                rs.getObject("created_by_customer_id", UUID.class),
                rs.getObject("submitted_by_customer_id", UUID.class),
                rs.getObject("approved_by_customer_id", UUID.class),
                rs.getObject("rejected_by_customer_id", UUID.class), rs.getString("content_digest"),
                rs.getString("approved_content_digest"));
    }

    private void requireDraftCreator(UUID actorCustomerId, BatchRow batch) {
        if (!batch.createdByCustomerId().equals(actorCustomerId)) {
            throw new OrganizationAuthorizationException("Only the payroll draft creator may edit or submit it.");
        }
    }

    private void requireDraft(BatchRow batch) {
        if (batch.status() != PayrollBatchStatus.DRAFT) {
            throw conflict("BATCH_NOT_DRAFT", "Payroll items can only be added while a batch is a draft.");
        }
    }

    private List<PayrollContentDigest.Item> digestItems(UUID batchId) {
        return jdbcTemplate.query("""
                SELECT id, sequence_no, employee_reference, employee_name, payee_account_id,
                       payee_owner_customer_id, amount_minor, currency
                FROM payroll_items WHERE batch_id = ? ORDER BY sequence_no
                """, (rs, row) -> new PayrollContentDigest.Item(rs.getObject("id", UUID.class),
                rs.getInt("sequence_no"), rs.getString("employee_reference"), rs.getString("employee_name"),
                rs.getObject("payee_account_id", UUID.class), rs.getObject("payee_owner_customer_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("currency")), batchId);
    }

    private long totalMinorFor(UUID batchId) {
        BigDecimal total = jdbcTemplate.queryForObject("""
                SELECT COALESCE(sum(amount_minor), 0) FROM payroll_items WHERE batch_id = ?
                """, BigDecimal.class, batchId);
        try {
            return total == null ? 0L : total.longValueExact();
        }
        catch (ArithmeticException exception) {
            throw new IllegalStateException("Persisted payroll total exceeds the supported integer range.", exception);
        }
    }

    private Counts countsFor(UUID batchId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
                       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
                       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count
                FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id
                WHERE i.batch_id = ?
                """, (rs, row) -> new Counts(rs.getInt("pending_count"), rs.getInt("succeeded_count"),
                rs.getInt("failed_count")), batchId);
    }

    private BatchView viewFor(UUID batchId) {
        return jdbcTemplate.query("""
                SELECT b.id, b.organization_id, b.name, b.payer_account_id, b.status,
                       count(i.id) AS item_count, COALESCE(sum(i.amount_minor), 0) AS total_minor,
                       count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
                       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
                       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count,
                       COALESCE(sum(i.amount_minor) FILTER (WHERE r.status = 'SUCCEEDED'), 0) AS succeeded_minor,
                       b.created_by_customer_id, b.submitted_by_customer_id, b.approved_by_customer_id,
                       b.rejected_by_customer_id, b.rejection_reason, b.content_digest,
                       b.approved_content_digest, b.created_at, b.submitted_at, b.approved_at,
                       b.rejected_at, b.started_at, b.completed_at
                FROM payroll_batches b
                LEFT JOIN payroll_items i ON i.batch_id = b.id
                LEFT JOIN payroll_item_results r ON r.item_id = i.id
                WHERE b.id = ?
                GROUP BY b.id
                """, (rs, row) -> batchView(rs), batchId).stream().findFirst()
                .orElseThrow(() -> new ApiNotFoundException("Payroll batch was not found."));
    }

    private BatchView batchView(ResultSet rs) throws SQLException {
        return new BatchView(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("name"), rs.getObject("payer_account_id", UUID.class), rs.getString("status"),
                rs.getInt("item_count"), new Money(rs.getLong("total_minor")).toNzdString(),
                rs.getInt("pending_count"), rs.getInt("succeeded_count"), rs.getInt("failed_count"),
                new Money(rs.getLong("succeeded_minor")).toNzdString(),
                rs.getObject("created_by_customer_id", UUID.class),
                rs.getObject("submitted_by_customer_id", UUID.class),
                rs.getObject("approved_by_customer_id", UUID.class),
                rs.getObject("rejected_by_customer_id", UUID.class), rs.getString("rejection_reason"),
                rs.getString("content_digest"), rs.getString("approved_content_digest"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("submitted_at")),
                instant(rs.getTimestamp("approved_at")), instant(rs.getTimestamp("rejected_at")),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")));
    }

    private ItemView itemView(ResultSet rs, int row) throws SQLException {
        return new ItemView(rs.getObject("id", UUID.class), rs.getInt("sequence_no"),
                rs.getString("employee_reference"), rs.getString("employee_name"),
                rs.getObject("payee_account_id", UUID.class), new Money(rs.getLong("amount_minor")).toNzdString(),
                rs.getString("status"), rs.getInt("attempt_count"), rs.getObject("transfer_id", UUID.class),
                rs.getString("error_code"), rs.getString("error_message"), instant(rs.getTimestamp("completed_at")));
    }

    private java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private PayrollException conflict(String code, String message) {
        return new PayrollException(code, message, HttpStatus.CONFLICT);
    }

    public record NewPayrollItem(String employeeReference, String employeeName, UUID payeeAccountId, String amount) {
    }

    public record BatchRow(UUID id, UUID organizationId, UUID payerAccountId, String name, PayrollBatchStatus status,
            UUID createdByCustomerId, UUID submittedByCustomerId, UUID approvedByCustomerId,
            UUID rejectedByCustomerId, String contentDigest, String approvedContentDigest) {
    }

    private record ValidatedItem(int sequenceNumber, String employeeReference, String employeeName,
            UUID payeeAccountId, UUID payeeOwnerCustomerId, long amountMinor) {
    }

    private record Counts(int pendingCount, int succeededCount, int failedCount) {
    }
}
