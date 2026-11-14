package nz.ac.ledgercore.organizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import nz.ac.ledgercore.clearing.ClearingCallbackService;
import nz.ac.ledgercore.clearing.ClearingDtos.Callback;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.transfers.TransferErrorCode;
import nz.ac.ledgercore.transfers.TransferException;
import nz.ac.ledgercore.transfers.TransferService;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationPostgresIntegrationTests {

    private static final Pattern TOKEN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern ID = Pattern.compile("\\\"id\\\":\\\"([0-9a-f-]{36})\\\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    OrganizationAuthorizationService authorizationService;

    @Autowired
    TransferService transferService;

    @Autowired
    ClearingCallbackService clearingCallbackService;

    @Test
    void corporateOwnershipMembershipRolesAndTransfersUsePostgres() throws Exception {
        Session admin = new Session();
        String adminId = admin.register("Corporate Admin");

        HttpResponse<String> organizationResponse = admin.call("POST", "/api/organizations",
                "{\"displayName\":\"Friday Payroll Ltd\"}", true);
        assertThat(organizationResponse.statusCode()).isEqualTo(201);
        String organizationId = capture(ID, organizationResponse.body());

        HttpResponse<String> accountResponse = admin.call("POST", "/api/organizations/" + organizationId + "/accounts",
                "{\"displayName\":\"Payroll account\"}", true);
        assertThat(accountResponse.statusCode()).isEqualTo(201);
        String corporateAccountId = capture(ID, accountResponse.body());
        UUID orgId = UUID.fromString(organizationId);
        UUID corporateId = UUID.fromString(corporateAccountId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT owner_customer_id IS NULL AND owner_organization_id = ?
                FROM accounts WHERE id = ?
                """, Boolean.class, orgId, corporateId)).isTrue();

        String personalTargetId = createPersonalAccount(admin, "Personal target");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT owner_customer_id = ? AND owner_organization_id IS NULL
                FROM accounts WHERE id = ?
                """, Boolean.class, UUID.fromString(adminId), UUID.fromString(personalTargetId))).isTrue();

        Session maker = new Session();
        String makerId = maker.register("Payroll Maker");
        HttpResponse<String> makerGrant = admin.call("POST",
                "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + maker.email + "\",\"roles\":[\"PAYROLL_MAKER\",\"PAYMENT_MAKER\"]}", true);
        assertThat(makerGrant.statusCode()).isEqualTo(201);

        Session approver = new Session();
        String approverId = approver.register("Payment Approver");
        HttpResponse<String> approverGrant = admin.call("POST",
                "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + approver.email + "\",\"roles\":[\"PAYROLL_APPROVER\",\"PAYMENT_APPROVER\"]}", true);
        assertThat(approverGrant.statusCode()).isEqualTo(201);

        Session viewer = new Session();
        String viewerId = viewer.register("Finance Viewer");
        HttpResponse<String> viewerGrant = admin.call("POST",
                "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + viewer.email + "\",\"roles\":[\"FINANCE_VIEWER\"]}", true);
        assertThat(viewerGrant.statusCode()).isEqualTo(201);

        Session operations = new Session();
        String operationsId = operations.register("Operations Recovery Actor");
        HttpResponse<String> operationsGrant = admin.call("POST",
                "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + operations.email + "\",\"roles\":[\"OPERATIONS\"]}", true);
        assertThat(operationsGrant.statusCode()).isEqualTo(201);

        Session outsider = new Session();
        outsider.register("External Customer");
        assertThat(outsider.call("GET", "/api/organizations/" + organizationId + "/accounts", null, false)
                .statusCode()).isEqualTo(404);
        assertThat(outsider.call("POST", "/api/organizations/" + organizationId + "/accounts",
                "{\"displayName\":\"Unauthorized\"}", true).statusCode()).isEqualTo(404);

        HttpResponse<String> visibleAccounts = viewer.call("GET",
                "/api/organizations/" + organizationId + "/accounts", null, false);
        assertThat(visibleAccounts.statusCode()).isEqualTo(200);
        assertThat(visibleAccounts.body()).contains(corporateAccountId, "1000.00");

        String transfer = "{\"sourceAccountId\":\"" + corporateAccountId + "\",\"targetAccountId\":\""
                + personalTargetId + "\",\"amount\":\"10.00\"}";
        String makerTransferKey = "maker-corporate-transfer";
        recordE8SqlSnapshot("before-denied-corporate-debit", corporateId, UUID.fromString(makerId),
                makerTransferKey, null);
        assertThat(viewer.call("POST", "/api/transfers", transfer, true, "viewer-corporate-transfer")
                .statusCode()).isEqualTo(404);
        assertThat(admin.call("POST", "/api/transfers", transfer, true, "admin-corporate-transfer")
                .statusCode()).isEqualTo(404);
        HttpResponse<String> posted = maker.call("POST", "/api/transfers", transfer, true, makerTransferKey);
        recordE8HttpResponse("payment-maker-general-transfer-denied", "POST", "/api/transfers", posted);
        assertThat(posted.statusCode()).isEqualTo(409);
        assertThat(posted.body()).contains("CORPORATE_APPROVAL_REQUIRED");
        HttpResponse<String> operationsDebit = operations.call("POST", "/api/transfers", transfer, true,
                "ops-corporate-transfer");
        recordE8HttpResponse("operations-general-transfer-denied", "POST", "/api/transfers", operationsDebit);
        assertThat(operationsDebit.statusCode()).isEqualTo(404);
        assertThatThrownBy(() -> transferService.transfer(UUID.fromString(makerId), corporateId,
                UUID.fromString(personalTargetId), new Money(1_000L), "maker-corporate-direct"))
                .isInstanceOf(TransferException.class)
                .satisfies(exception -> assertThat(((TransferException) exception).code())
                        .isEqualTo(TransferErrorCode.CORPORATE_APPROVAL_REQUIRED));
        assertThatThrownBy(() -> transferService.transfer(UUID.fromString(operationsId),
                corporateId, UUID.fromString(personalTargetId), new Money(1_000L), "ops-corporate-direct"))
                .isInstanceOf(TransferException.class)
                .satisfies(exception -> assertThat(((TransferException) exception).code())
                        .isEqualTo(TransferErrorCode.ACCOUNT_NOT_FOUND));
        recordE8SqlSnapshot("after-denied-corporate-debit", corporateId, UUID.fromString(makerId),
                makerTransferKey, null);

        long clearingPayableBeforePayment = jdbcTemplate.queryForObject("""
                SELECT balance_minor FROM account_balance_projection
                WHERE ledger_account_id = '00000000-0000-5000-8000-000000000002'
                """, Long.class);
        String paymentRequest = "{\"sourceAccountId\":\"" + corporateAccountId
                + "\",\"beneficiaryName\":\"External Supplier\",\"beneficiaryAccountReference\":\"NZ-12345\","
                + "\"beneficiaryRoutingCode\":\"BANK-01\",\"amount\":\"10.00\"}";
        assertThat(operations.call("POST", "/api/organizations/" + organizationId + "/payments", paymentRequest, true)
                .statusCode()).isEqualTo(403);
        HttpResponse<String> createdPayment = maker.call("POST", "/api/organizations/" + organizationId + "/payments",
                paymentRequest, true);
        recordE8HttpResponse("payment-draft-created", "POST",
                "/api/organizations/" + organizationId + "/payments", createdPayment);
        assertThat(createdPayment.statusCode()).isEqualTo(201);
        String paymentId = capture(ID, createdPayment.body());
        assertThat(createdPayment.body()).contains("\"status\":\"DRAFT\"",
                "\"beneficiaryAccountReference\":\"NZ-12345\"", "\"beneficiaryRoutingCode\":\"BANK-01\"");
        assertThat(viewer.call("POST", "/api/organizations/" + organizationId + "/payments", paymentRequest, true)
                .statusCode()).isEqualTo(403);
        HttpResponse<String> submittedPayment = maker.call("POST", "/api/payments/" + paymentId + "/submit", null, true);
        recordE8HttpResponse("payment-submitted", "POST", "/api/payments/" + paymentId + "/submit", submittedPayment);
        assertThat(submittedPayment.statusCode()).isEqualTo(200);
        assertThat(submittedPayment.body()).contains("\"status\":\"SUBMITTED\"",
                "\"beneficiaryAccountReference\":\"NZ-12345\"", "\"beneficiaryRoutingCode\":\"BANK-01\"");
        assertThat(maker.call("POST", "/api/payments/" + paymentId + "/submit", null, true).statusCode())
                .isEqualTo(200);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE corporate_payment_intents "
                + "SET beneficiary_account_reference = ? WHERE id = ?", "NZ-TAMPERED", UUID.fromString(paymentId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE corporate_payment_intents "
                + "SET amount_minor = amount_minor + 1 WHERE id = ?", UUID.fromString(paymentId)))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT beneficiary_account_reference "
                + "FROM corporate_payment_intents WHERE id = ?", String.class, UUID.fromString(paymentId)))
                .isEqualTo("NZ-12345");
        UUID paymentUuid = UUID.fromString(paymentId);
        recordE8SqlSnapshot("after-submission-before-approval", corporateId, UUID.fromString(makerId),
                makerTransferKey, paymentUuid);
        assertPaymentInstructionVisibility(maker, "payment-maker", organizationId, paymentId, true);
        assertPaymentInstructionVisibility(approver, "payment-approver", organizationId, paymentId, true);
        assertPaymentInstructionVisibility(admin, "organization-admin", organizationId, paymentId, false);
        assertPaymentInstructionVisibility(viewer, "finance-viewer", organizationId, paymentId, false);
        assertPaymentInstructionVisibility(operations, "operations", organizationId, paymentId, false);
        HttpResponse<String> outsiderGet = outsider.call("GET", "/api/payments/" + paymentId, null, false);
        recordE8HttpResponse("cross-tenant-payment-get", "GET", "/api/payments/" + paymentId, outsiderGet);
        assertThat(outsiderGet.statusCode()).isEqualTo(404);
        HttpResponse<String> outsiderList = outsider.call("GET",
                "/api/organizations/" + organizationId + "/payments", null, false);
        recordE8HttpResponse("cross-tenant-payment-list", "GET",
                "/api/organizations/" + organizationId + "/payments", outsiderList);
        assertThat(outsiderList.statusCode()).isEqualTo(404);
        HttpResponse<String> outsiderRecent = outsider.call("GET", "/api/payments", null, false);
        recordE8HttpResponse("cross-tenant-recent-payments", "GET", "/api/payments", outsiderRecent);
        assertThat(outsiderRecent.body()).isEqualTo("[]");
        assertThat(admin.call("GET", "/api/organizations/" + organizationId + "/accounts", null, false).body())
                .contains("\"balance\":\"1000.00\"");
        HttpResponse<String> makerApproval = maker.call("POST", "/api/payments/" + paymentId + "/approve", null, true);
        recordE8HttpResponse("maker-self-approval-denied", "POST", "/api/payments/" + paymentId + "/approve",
                makerApproval);
        assertThat(makerApproval.statusCode()).isEqualTo(403);
        HttpResponse<String> adminApproval = admin.call("POST", "/api/payments/" + paymentId + "/approve", null, true);
        recordE8HttpResponse("organization-admin-approval-denied", "POST",
                "/api/payments/" + paymentId + "/approve", adminApproval);
        assertThat(adminApproval.statusCode()).isEqualTo(403);
        HttpResponse<String> viewerApproval = viewer.call("POST", "/api/payments/" + paymentId + "/approve", null, true);
        recordE8HttpResponse("finance-viewer-approval-denied", "POST",
                "/api/payments/" + paymentId + "/approve", viewerApproval);
        assertThat(viewerApproval.statusCode()).isEqualTo(403);
        HttpResponse<String> operationsApproval = operations.call("POST", "/api/payments/" + paymentId + "/approve",
                null, true);
        recordE8HttpResponse("operations-approval-denied", "POST",
                "/api/payments/" + paymentId + "/approve", operationsApproval);
        assertThat(operationsApproval.statusCode()).isEqualTo(403);
        recordE8SqlSnapshot("before-independent-approval", corporateId, UUID.fromString(makerId),
                makerTransferKey, paymentUuid);
        HttpResponse<String> approvedPayment = approver.call("POST", "/api/payments/" + paymentId + "/approve", null, true);
        recordE8HttpResponse("independent-approval", "POST", "/api/payments/" + paymentId + "/approve",
                approvedPayment);
        assertThat(approvedPayment.statusCode()).isEqualTo(200);
        assertThat(approvedPayment.body()).contains("\"status\":\"PENDING_CLEARING\"", "\"externalStatus\":null");
        assertThat(approvedPayment.body()).contains("\"beneficiaryAccountReference\":\"NZ-12345\"",
                "\"beneficiaryRoutingCode\":\"BANK-01\"");
        assertThat(approver.call("POST", "/api/payments/" + paymentId + "/approve", null, true).statusCode())
                .isEqualTo(200);
        assertThat(admin.call("GET", "/api/organizations/" + organizationId + "/accounts", null, false).body())
                .contains("\"balance\":\"990.00\"");
        assertThat(admin.call("GET", "/api/accounts/" + personalTargetId, null, false).body())
                .contains("\"balance\":\"1000.00\"");
        recordE8SqlSnapshot("after-independent-approval", corporateId, UUID.fromString(makerId),
                makerTransferKey, paymentUuid);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM transfers WHERE source_account_id = ?
                """, Integer.class, corporateId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM corporate_payment_intents
                WHERE id = ? AND operation_id = id AND created_by_customer_id = ?
                  AND submitted_by_customer_id = ? AND approved_by_customer_id = ?
                  AND intent_digest = approved_digest AND status = 'PENDING_CLEARING'
                """, Integer.class, paymentUuid, UUID.fromString(makerId), UUID.fromString(makerId),
                UUID.fromString(approverId))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM journal_entries e JOIN postings p ON p.journal_entry_id = e.id
                WHERE e.entry_type = 'CORPORATE_PAYMENT_BOOKING'
                  AND e.reference = ? AND p.amount_minor = 1000
                """, Integer.class, "corporate-payment:" + paymentId + ":booking")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT balance_minor FROM account_balance_projection WHERE ledger_account_id =
                    '00000000-0000-5000-8000-000000000002'
                """, Long.class)).isEqualTo(clearingPayableBeforePayment + 1_000L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM clearing_instructions WHERE payment_intent_id = ? AND operation_id = ?
                """, Integer.class, paymentUuid, paymentUuid)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ? AND event_type IN ('corporate.payment.created',
                    'corporate.payment.submitted', 'corporate.payment.approved')
                """, Integer.class, paymentUuid)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ? AND organization_id = ? AND actor_customer_id = ?
                  AND event_type IN ('corporate.payment.created', 'corporate.payment.submitted')
                """, Integer.class, paymentUuid, orgId, UUID.fromString(makerId))).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ? AND organization_id = ? AND actor_customer_id = ?
                  AND event_type IN ('corporate.payment.approved', 'clearing.instruction.ready')
                """, Integer.class, paymentUuid, orgId, UUID.fromString(approverId))).isEqualTo(2);
        String paymentAuditFacts = jdbcTemplate.queryForObject("""
                SELECT coalesce(string_agg(details::text, ' '), '')
                FROM audit_events WHERE organization_id = ? AND resource_id = ?
                """, String.class, orgId, paymentUuid);
        String paymentEventFacts = jdbcTemplate.queryForObject("""
                SELECT coalesce(string_agg(payload::text, ' '), '')
                FROM outbox_events WHERE organization_id = ? AND aggregate_id = ?
                """, String.class, orgId, paymentUuid);
        assertThat(paymentAuditFacts).doesNotContain("NZ-12345", "BANK-01");
        assertThat(paymentEventFacts).doesNotContain("NZ-12345", "BANK-01");

        Callback settled = callback(paymentUuid, 1, "SETTLED");
        assertThat(deliver(settled)).isEqualTo("ACCEPTED");
        assertThat(deliver(settled)).isEqualTo("DUPLICATE");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?",
                String.class, paymentUuid)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_entries WHERE reference = ?",
                Integer.class, "corporate-payment:" + paymentId + ":settlement")).isEqualTo(1);

        Callback alteredDuplicate = new Callback(settled.callbackId(), paymentUuid, paymentUuid,
                jdbcTemplate.queryForObject("SELECT intent_digest FROM corporate_payment_intents WHERE id = ?",
                        String.class, paymentUuid), 2, "REJECTED", settled.simulatorReference(), Instant.now());
        assertThat(deliver(alteredDuplicate)).isEqualTo("EXCEPTION_RECORDED");
        assertThat(deliver(callback(paymentUuid, 2, "REJECTED"))).isEqualTo("EXCEPTION_RECORDED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?",
                String.class, paymentUuid)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_entries WHERE reference = ?",
                Integer.class, "corporate-payment:" + paymentId + ":refund")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM reconciliation_exceptions
                WHERE operation_id = ? AND category IN ('CLEARING_CALLBACK_ID_REUSE', 'ILLEGAL_CLEARING_TRANSITION')
                """, Integer.class, paymentUuid)).isEqualTo(2);

        String unknownPaymentId = createApprovedPayment(maker, approver, organizationId, corporateAccountId,
                "SIM-UNKNOWN", "5.00", "Uncertain Supplier");
        UUID unknownPaymentUuid = UUID.fromString(unknownPaymentId);
        assertThat(deliver(new Callback(UUID.randomUUID(), unknownPaymentUuid, unknownPaymentUuid,
                "f".repeat(64), 1, "SETTLED", "SIM-TEST-WRONG-DIGEST", Instant.now())))
                .isEqualTo("EXCEPTION_RECORDED");
        assertThat(deliver(callback(unknownPaymentUuid, 2, "SETTLED"))).isEqualTo("EXCEPTION_RECORDED");
        assertThat(jdbcTemplate.queryForObject("SELECT external_status FROM corporate_payment_intents WHERE id = ?",
                String.class, unknownPaymentUuid)).isNull();
        Callback unknown = callback(unknownPaymentUuid, 1, "UNKNOWN");
        assertThat(deliver(unknown)).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?",
                String.class, unknownPaymentUuid)).isEqualTo("UNKNOWN");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_entries WHERE reference = ?",
                Integer.class, "corporate-payment:" + unknownPaymentId + ":refund")).isZero();
        assertThat(deliver(callback(unknownPaymentUuid, 1, "PENDING"))).isEqualTo("EXCEPTION_RECORDED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?",
                String.class, unknownPaymentUuid)).isEqualTo("UNKNOWN");
        assertThat(deliver(callback(unknownPaymentUuid, 2, "SETTLED"))).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?",
                String.class, unknownPaymentUuid)).isEqualTo("COMPLETED");

        String rejectedPaymentId = createApprovedPayment(maker, approver, organizationId, corporateAccountId,
                "SIM-REJECT", "5.00", "Rejected Supplier");
        UUID rejectedPaymentUuid = UUID.fromString(rejectedPaymentId);
        assertThat(deliver(callback(rejectedPaymentUuid, 1, "REJECTED"))).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?",
                String.class, rejectedPaymentUuid)).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_entries WHERE reference = ?",
                Integer.class, "corporate-payment:" + rejectedPaymentId + ":refund")).isEqualTo(1);

        HttpResponse<String> adminSelfGrant = admin.call("POST",
                "/api/organizations/" + organizationId + "/members/" + adminId + "/roles",
                "{\"role\":\"PAYMENT_APPROVER\"}", true);
        assertThat(adminSelfGrant.statusCode()).isEqualTo(403);
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + admin.email + "\",\"roles\":[\"PAYMENT_APPROVER\"]}", true)
                .statusCode()).isEqualTo(403);

        UUID makerUuid = UUID.fromString(makerId);
        assertThatThrownBy(() -> authorizationService.requireCanApprovePayroll(makerUuid, orgId, makerUuid))
                .isInstanceOf(OrganizationAuthorizationException.class)
                .hasMessageContaining("role is required");
        UUID approverUuid = UUID.fromString(approverId);
        assertThatThrownBy(() -> authorizationService.requireCanApprovePayroll(approverUuid, orgId, approverUuid))
                .isInstanceOf(OrganizationAuthorizationException.class)
                .hasMessageContaining("cannot approve their own batch");
        assertThatThrownBy(() -> authorizationService.requireCanApprovePayroll(UUID.fromString(viewerId), orgId, makerUuid))
                .isInstanceOf(OrganizationAuthorizationException.class);
        authorizationService.requireCanApprovePayroll(approverUuid, orgId, makerUuid);

        HttpResponse<String> otherOrg = outsider.call("POST", "/api/organizations",
                "{\"displayName\":\"Other Company\"}", true);
        assertThat(otherOrg.statusCode()).isEqualTo(201);
        String otherOrgId = capture(ID, otherOrg.body());
        assertThat(admin.call("GET", "/api/organizations/" + otherOrgId + "/accounts", null, false)
                .statusCode()).isEqualTo(404);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO accounts (id, owner_customer_id, owner_organization_id, display_name, status, currency)
                VALUES (?, NULL, NULL, 'No owner', 'OPEN', 'NZD')
                """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO accounts (id, owner_customer_id, owner_organization_id, display_name, status, currency)
                VALUES (?, ?, ?, 'Two owners', 'OPEN', 'NZD')
                """, UUID.randomUUID(), UUID.fromString(adminId), orgId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void suspendedPaymentApproverRequiresExplicitAuditedReactivation() throws Exception {
        Session admin = new Session();
        String adminId = admin.register("E8 Governance Admin");
        HttpResponse<String> organization = admin.call("POST", "/api/organizations",
                "{\"displayName\":\"E8 Governance " + UUID.randomUUID() + "\"}", true);
        assertThat(organization.statusCode()).isEqualTo(201);
        String organizationId = capture(ID, organization.body());
        UUID orgId = UUID.fromString(organizationId);

        HttpResponse<String> account = admin.call("POST", "/api/organizations/" + organizationId + "/accounts",
                "{\"displayName\":\"Governance operating account\"}", true);
        assertThat(account.statusCode()).isEqualTo(201);
        String accountId = capture(ID, account.body());

        Session maker = new Session();
        maker.register("E8 Governance Maker");
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + maker.email + "\",\"roles\":[\"PAYMENT_MAKER\"]}", true)
                .statusCode()).isEqualTo(201);

        Session approver = new Session();
        String approverId = approver.register("E8 Governance Approver");
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + approver.email + "\",\"roles\":[\"PAYMENT_APPROVER\"]}", true)
                .statusCode()).isEqualTo(201);

        String request = "{\"sourceAccountId\":\"" + accountId
                + "\",\"beneficiaryName\":\"Synthetic Supplier\",\"beneficiaryAccountReference\":\"NZ-E8-1\","
                + "\"beneficiaryRoutingCode\":\"BANK-E8\",\"amount\":\"1.00\"}";
        HttpResponse<String> draft = maker.call("POST", "/api/organizations/" + organizationId + "/payments",
                request, true);
        assertThat(draft.statusCode()).isEqualTo(201);
        String paymentId = capture(ID, draft.body());
        assertThat(maker.call("POST", "/api/payments/" + paymentId + "/submit", null, true).statusCode())
                .isEqualTo(200);

        UUID approverUuid = UUID.fromString(approverId);
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members/" + approverId
                + "/suspend", null, true).statusCode()).isEqualTo(200);
        assertThat(approver.call("POST", "/api/payments/" + paymentId + "/approve", null, true).statusCode())
                .isEqualTo(404);
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + approver.email + "\",\"roles\":[\"PAYMENT_APPROVER\"]}", true)
                .statusCode()).isEqualTo(403);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM organization_memberships "
                + "WHERE organization_id = ? AND customer_id = ?", String.class, orgId, approverUuid))
                .isEqualTo("SUSPENDED");
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members/" + adminId
                + "/reactivate", null, true).statusCode()).isEqualTo(403);

        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members/" + approverId
                + "/reactivate", null, true).statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM organization_memberships "
                + "WHERE organization_id = ? AND customer_id = ?", String.class, orgId, approverUuid))
                .isEqualTo("ACTIVE");
        HttpResponse<String> approved = approver.call("POST", "/api/payments/" + paymentId + "/approve", null, true);
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(approved.body()).contains("\"approvedByCustomerId\":\"" + approverId + "\"");

        UUID adminUuid = UUID.fromString(adminId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE actor_customer_id = ? AND organization_id = ? AND resource_id = ?
                  AND action = 'ORGANIZATION_ROLE_GRANTED' AND details->>'role' = 'PAYMENT_APPROVER'
                """, Integer.class, adminUuid, orgId, approverUuid)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE actor_customer_id = ? AND organization_id = ? AND resource_id = ?
                  AND action = 'ORGANIZATION_MEMBER_SUSPENDED'
                """, Integer.class, adminUuid, orgId, approverUuid)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE actor_customer_id = ? AND organization_id = ? AND resource_id = ?
                  AND action = 'ORGANIZATION_MEMBER_REACTIVATED'
                """, Integer.class, adminUuid, orgId, approverUuid)).isEqualTo(1);
    }

    @Test
    void concurrentApprovalRetriesCreateOneFinancialBookingAndClearingInstruction() throws Exception {
        Session admin = new Session();
        admin.register("E8 Concurrency Admin");
        HttpResponse<String> organization = admin.call("POST", "/api/organizations",
                "{\"displayName\":\"E8 Concurrency " + UUID.randomUUID() + "\"}", true);
        assertThat(organization.statusCode()).isEqualTo(201);
        String organizationId = capture(ID, organization.body());
        UUID orgId = UUID.fromString(organizationId);
        HttpResponse<String> account = admin.call("POST", "/api/organizations/" + organizationId + "/accounts",
                "{\"displayName\":\"Concurrency payer\"}", true);
        assertThat(account.statusCode()).isEqualTo(201);
        String accountId = capture(ID, account.body());

        Session maker = new Session();
        maker.register("E8 Concurrency Maker");
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + maker.email + "\",\"roles\":[\"PAYMENT_MAKER\"]}", true)
                .statusCode()).isEqualTo(201);
        Session approver = new Session();
        String approverId = approver.register("E8 Concurrency Approver");
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + approver.email + "\",\"roles\":[\"PAYMENT_APPROVER\"]}", true)
                .statusCode()).isEqualTo(201);

        String request = "{\"sourceAccountId\":\"" + accountId
                + "\",\"beneficiaryName\":\"Concurrent Supplier\",\"beneficiaryAccountReference\":\"NZ-E8-RACE\","
                + "\"beneficiaryRoutingCode\":\"BANK-E8\",\"amount\":\"2.00\"}";
        HttpResponse<String> draft = maker.call("POST", "/api/organizations/" + organizationId + "/payments",
                request, true);
        assertThat(draft.statusCode()).isEqualTo(201);
        UUID paymentId = UUID.fromString(capture(ID, draft.body()));
        assertThat(maker.call("POST", "/api/payments/" + paymentId + "/submit", null, true).statusCode())
                .isEqualTo(200);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to race the approval requests.");
                }
                return approver.call("POST", "/api/payments/" + paymentId + "/approve", null, true);
            });
            Future<HttpResponse<String>> second = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to race the approval requests.");
                }
                return approver.call("POST", "/api/payments/" + paymentId + "/approve", null, true);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            HttpResponse<String> firstResponse = first.get(15, TimeUnit.SECONDS);
            HttpResponse<String> secondResponse = second.get(15, TimeUnit.SECONDS);
            assertThat(firstResponse.statusCode()).isEqualTo(200);
            assertThat(secondResponse.statusCode()).isEqualTo(200);
        }
        finally {
            start.countDown();
            executor.shutdownNow();
        }

        UUID approverUuid = UUID.fromString(approverId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM corporate_payment_intents
                WHERE id = ? AND approved_by_customer_id = ? AND intent_digest = approved_digest
                  AND status = 'PENDING_CLEARING'
                """, Integer.class, paymentId, approverUuid)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM journal_entries e JOIN postings p ON p.journal_entry_id = e.id
                WHERE e.reference = ? AND e.entry_type = 'CORPORATE_PAYMENT_BOOKING'
                """, Integer.class, "corporate-payment:" + paymentId + ":booking")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM clearing_instructions "
                + "WHERE payment_intent_id = ? AND operation_id = ?", Integer.class, paymentId, paymentId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE organization_id = ? AND resource_id = ? AND action = 'CORPORATE_PAYMENT_APPROVED_AND_BOOKED'
                """, Integer.class, orgId, paymentId)).isEqualTo(1);
    }

    private String createApprovedPayment(Session maker, Session approver, String organizationId,
            String corporateAccountId, String routingCode, String amount, String beneficiaryName) throws Exception {
        String request = "{\"sourceAccountId\":\"" + corporateAccountId + "\",\"beneficiaryName\":\""
                + beneficiaryName + "\",\"beneficiaryAccountReference\":\"NZ-" + UUID.randomUUID()
                + "\",\"beneficiaryRoutingCode\":\"" + routingCode + "\",\"amount\":\"" + amount + "\"}";
        HttpResponse<String> draft = maker.call("POST", "/api/organizations/" + organizationId + "/payments", request, true);
        assertThat(draft.statusCode()).isEqualTo(201);
        String paymentId = capture(ID, draft.body());
        assertThat(maker.call("POST", "/api/payments/" + paymentId + "/submit", null, true).statusCode())
                .isEqualTo(200);
        assertThat(approver.call("POST", "/api/payments/" + paymentId + "/approve", null, true).statusCode())
                .isEqualTo(200);
        return paymentId;
    }

    private void assertPaymentInstructionVisibility(Session session, String role, String organizationId,
            String paymentId, boolean visible) throws Exception {
        String[] paths = {
                "/api/payments/" + paymentId,
                "/api/organizations/" + organizationId + "/payments?page=0&size=20",
                "/api/payments?limit=20"
        };
        for (int index = 0; index < paths.length; index++) {
            String path = paths[index];
            HttpResponse<String> response = session.call("GET", path, null, false);
            recordE8HttpResponse(role + "-payment-read-" + index, "GET", path, response);
            assertThat(response.statusCode()).as("GET %s for %s", path, session.email).isEqualTo(200);
            if (visible) {
                assertThat(response.body()).contains("\"beneficiaryAccountReference\":\"NZ-12345\"",
                        "\"beneficiaryRoutingCode\":\"BANK-01\"");
            }
            else {
                assertThat(response.body()).contains("\"beneficiaryAccountReference\":\"[REDACTED]\"",
                        "\"beneficiaryRoutingCode\":\"[REDACTED]\"")
                        .doesNotContain("NZ-12345", "BANK-01");
            }
        }
    }

    private void recordE8HttpResponse(String name, String method, String path, HttpResponse<String> response)
            throws IOException {
        String configuredDirectory = System.getProperty("ledgercore.e8.evidence-dir");
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return;
        }
        Path directory = Path.of(configuredDirectory);
        Files.createDirectories(directory);
        String artifact = "HTTP " + response.statusCode() + "\n" + method + " " + path + "\n\n"
                + response.body() + "\n";
        Files.writeString(directory.resolve(name + ".http.txt"), artifact, StandardCharsets.UTF_8);
    }

    private void recordE8SqlSnapshot(String name, UUID corporateAccountId, UUID makerCustomerId,
            String idempotencyKey, UUID paymentId) throws Exception {
        String configuredDirectory = System.getProperty("ledgercore.e8.evidence-dir");
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return;
        }
        Path directory = Path.of(configuredDirectory);
        Files.createDirectories(directory);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshot", name);
        snapshot.put("corporateAccountId", corporateAccountId);
        snapshot.put("corporateBalanceMinor", jdbcTemplate.queryForObject("""
                SELECT p.balance_minor FROM ledger_accounts la
                JOIN account_balance_projection p ON p.ledger_account_id = la.id
                WHERE la.customer_account_id = ? AND la.kind = 'CUSTOMER_DEPOSIT'
                """, Long.class, corporateAccountId));
        snapshot.put("corporateTransferCount", jdbcTemplate.queryForObject("""
                SELECT count(*) FROM transfers WHERE source_account_id = ?
                """, Long.class, corporateAccountId));
        snapshot.put("corporateTransferIdempotencyCount", jdbcTemplate.queryForObject("""
                SELECT count(*) FROM idempotency_records
                WHERE owner_customer_id = ? AND operation = 'TRANSFER' AND idempotency_key = ?
                """, Long.class, makerCustomerId, idempotencyKey));
        snapshot.put("journalEntryCount", jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_entries", Long.class));
        snapshot.put("postingCount", jdbcTemplate.queryForObject("SELECT count(*) FROM postings", Long.class));
        if (paymentId != null) {
            snapshot.put("payment", jdbcTemplate.queryForMap("""
                    SELECT status, created_by_customer_id, submitted_by_customer_id, approved_by_customer_id,
                           intent_digest = approved_digest AS approval_digest_matches, amount_minor, currency
                    FROM corporate_payment_intents WHERE id = ?
                    """, paymentId));
            snapshot.put("bookingEntryCount", jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM journal_entries
                    WHERE reference = ? AND entry_type = 'CORPORATE_PAYMENT_BOOKING'
                    """, Long.class, "corporate-payment:" + paymentId + ":booking"));
            snapshot.put("bookingPostingCount", jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM postings p JOIN journal_entries e ON e.id = p.journal_entry_id
                    WHERE e.reference = ?
                    """, Long.class, "corporate-payment:" + paymentId + ":booking"));
            snapshot.put("bookingBalanced", jdbcTemplate.queryForObject("""
                    SELECT coalesce(sum(amount_minor) FILTER (WHERE side = 'DEBIT'), 0)
                         = coalesce(sum(amount_minor) FILTER (WHERE side = 'CREDIT'), 0)
                    FROM postings p JOIN journal_entries e ON e.id = p.journal_entry_id
                    WHERE e.reference = ?
                    """, Boolean.class, "corporate-payment:" + paymentId + ":booking"));
            snapshot.put("clearingInstructionCount", jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM clearing_instructions WHERE payment_intent_id = ?
                    """, Long.class, paymentId));
            snapshot.put("paymentAuditAndOutboxFacts", jdbcTemplate.queryForObject("""
                    SELECT coalesce(string_agg(fact::text, ' '), '')
                    FROM (
                        SELECT details AS fact FROM audit_events WHERE resource_id = ?
                        UNION ALL
                        SELECT payload AS fact FROM outbox_events WHERE aggregate_id = ?
                    ) payment_facts
                    """, String.class, paymentId, paymentId));
        }
        String json = JsonMapper.builder().build().writeValueAsString(snapshot);
        Files.writeString(directory.resolve(name + ".sql-snapshot.json"), json + "\n", StandardCharsets.UTF_8);
    }

    private Callback callback(UUID paymentId, long sequenceNo, String status) {
        String digest = jdbcTemplate.queryForObject(
                "SELECT intent_digest FROM corporate_payment_intents WHERE id = ?", String.class, paymentId);
        return new Callback(UUID.randomUUID(), paymentId, paymentId, digest, sequenceNo, status,
                "SIM-TEST-" + paymentId.toString().substring(0, 8), Instant.now());
    }

    private String deliver(Callback callback) throws Exception {
        return clearingCallbackService.receive(callback, JsonMapper.builder().build().writeValueAsBytes(callback)).result();
    }

    private String createPersonalAccount(Session session, String name) throws Exception {
        HttpResponse<String> response = session.call("POST", "/api/accounts",
                "{\"displayName\":\"" + name + "\"}", true);
        assertThat(response.statusCode()).isEqualTo(201);
        return capture(ID, response.body());
    }

    private String capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private final class Session {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        private String csrfToken;
        private String email;

        private String register(String displayName) throws Exception {
            refreshCsrf();
            email = "org-" + UUID.randomUUID() + "@example.test";
            HttpResponse<String> response = call("POST", "/api/auth/register",
                    "{\"email\":\"" + email + "\",\"password\":\"Organization-Test-Password!\",\"displayName\":\""
                            + displayName + "\"}", true);
            assertThat(response.statusCode()).isEqualTo(201);
            return capture(ID, response.body());
        }

        private void refreshCsrf() throws Exception {
            HttpResponse<String> response = call("GET", "/api/auth/csrf", null, false);
            assertThat(response.statusCode()).isEqualTo(200);
            csrfToken = capture(TOKEN, response.body());
        }

        private HttpResponse<String> call(String method, String path, String body, boolean withCsrf) throws Exception {
            return call(method, path, body, withCsrf, null);
        }

        private HttpResponse<String> call(String method, String path, String body, boolean withCsrf,
                String idempotencyKey) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            else {
                request.header("Content-Type", "application/json").method(method,
                        HttpRequest.BodyPublishers.ofString(body));
            }
            if (withCsrf && csrfToken != null) {
                request.header("X-CSRF-TOKEN", csrfToken);
            }
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
