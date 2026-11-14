package nz.ac.ledgercore.organizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.transfers.TransferErrorCode;
import nz.ac.ledgercore.transfers.TransferException;
import nz.ac.ledgercore.transfers.TransferService;

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
        assertThat(viewer.call("POST", "/api/transfers", transfer, true, "viewer-corporate-transfer")
                .statusCode()).isEqualTo(404);
        assertThat(admin.call("POST", "/api/transfers", transfer, true, "admin-corporate-transfer")
                .statusCode()).isEqualTo(404);
        HttpResponse<String> posted = maker.call("POST", "/api/transfers", transfer, true, "maker-corporate-transfer");
        assertThat(posted.statusCode()).isEqualTo(409);
        assertThat(posted.body()).contains("CORPORATE_APPROVAL_REQUIRED");
        assertThatThrownBy(() -> transferService.transfer(UUID.fromString(makerId), corporateId,
                UUID.fromString(personalTargetId), new Money(1_000L), "maker-corporate-direct"))
                .isInstanceOf(TransferException.class)
                .satisfies(exception -> assertThat(((TransferException) exception).code())
                        .isEqualTo(TransferErrorCode.CORPORATE_APPROVAL_REQUIRED));

        String paymentRequest = "{\"sourceAccountId\":\"" + corporateAccountId
                + "\",\"beneficiaryName\":\"External Supplier\",\"beneficiaryAccountReference\":\"NZ-12345\","
                + "\"beneficiaryRoutingCode\":\"BANK-01\",\"amount\":\"10.00\"}";
        HttpResponse<String> createdPayment = maker.call("POST", "/api/organizations/" + organizationId + "/payments",
                paymentRequest, true);
        assertThat(createdPayment.statusCode()).isEqualTo(201);
        String paymentId = capture(ID, createdPayment.body());
        assertThat(createdPayment.body()).contains("\"status\":\"DRAFT\"");
        HttpResponse<String> submittedPayment = maker.call("POST", "/api/payments/" + paymentId + "/submit", null, true);
        assertThat(submittedPayment.statusCode()).isEqualTo(200);
        assertThat(submittedPayment.body()).contains("\"status\":\"SUBMITTED\"");
        assertThat(maker.call("POST", "/api/payments/" + paymentId + "/submit", null, true).statusCode())
                .isEqualTo(200);
        assertThat(admin.call("GET", "/api/organizations/" + organizationId + "/accounts", null, false).body())
                .contains("\"balance\":\"1000.00\"");
        HttpResponse<String> makerApproval = maker.call("POST", "/api/payments/" + paymentId + "/approve", null, true);
        assertThat(makerApproval.statusCode()).isEqualTo(403);
        HttpResponse<String> approvedPayment = approver.call("POST", "/api/payments/" + paymentId + "/approve", null, true);
        assertThat(approvedPayment.statusCode()).isEqualTo(200);
        assertThat(approvedPayment.body()).contains("\"status\":\"PENDING_CLEARING\"", "\"externalStatus\":null");
        assertThat(approver.call("POST", "/api/payments/" + paymentId + "/approve", null, true).statusCode())
                .isEqualTo(200);
        assertThat(admin.call("GET", "/api/organizations/" + organizationId + "/accounts", null, false).body())
                .contains("\"balance\":\"990.00\"");
        assertThat(admin.call("GET", "/api/accounts/" + personalTargetId, null, false).body())
                .contains("\"balance\":\"1000.00\"");
        UUID paymentUuid = UUID.fromString(paymentId);
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
                """, Long.class)).isEqualTo(1_000L);
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

        HttpResponse<String> adminSelfGrant = admin.call("POST",
                "/api/organizations/" + organizationId + "/members/" + adminId + "/roles",
                "{\"role\":\"PAYMENT_APPROVER\"}", true);
        assertThat(adminSelfGrant.statusCode()).isEqualTo(403);

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
