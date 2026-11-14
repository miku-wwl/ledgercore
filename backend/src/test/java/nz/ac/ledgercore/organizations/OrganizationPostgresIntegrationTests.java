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
                "{\"email\":\"" + maker.email + "\",\"roles\":[\"PAYROLL_MAKER\",\"PAYROLL_APPROVER\"]}", true);
        assertThat(makerGrant.statusCode()).isEqualTo(201);

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

        transferService.transfer(UUID.fromString(makerId), corporateId, UUID.fromString(personalTargetId),
                new Money(1_000L), "maker-corporate-transfer");
        HttpResponse<String> posted = maker.call("POST", "/api/transfers", transfer, true, "maker-corporate-transfer");
        assertThat(posted.statusCode()).isEqualTo(200);
        assertThat(posted.body()).contains("\"status\":\"POSTED\"", "\"amount\":\"10.00\"");
        assertThat(admin.call("GET", "/api/organizations/" + organizationId + "/accounts", null, false).body())
                .contains("\"balance\":\"990.00\"");
        assertThat(admin.call("GET", "/api/accounts/" + personalTargetId, null, false).body())
                .contains("\"balance\":\"1010.00\"");

        HttpResponse<String> adminApproverGrant = admin.call("POST",
                "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + admin.email + "\",\"roles\":[\"PAYROLL_APPROVER\"]}", true);
        assertThat(adminApproverGrant.statusCode()).isEqualTo(201);

        UUID makerUuid = UUID.fromString(makerId);
        assertThatThrownBy(() -> authorizationService.requireCanApprovePayroll(makerUuid, orgId, makerUuid))
                .isInstanceOf(OrganizationAuthorizationException.class)
                .hasMessageContaining("cannot approve their own batch");
        assertThatThrownBy(() -> authorizationService.requireCanApprovePayroll(UUID.fromString(viewerId), orgId, makerUuid))
                .isInstanceOf(OrganizationAuthorizationException.class);
        authorizationService.requireCanApprovePayroll(UUID.fromString(adminId), orgId, makerUuid);

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
