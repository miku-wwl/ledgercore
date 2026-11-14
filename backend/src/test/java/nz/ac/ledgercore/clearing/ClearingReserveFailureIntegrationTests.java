package nz.ac.ledgercore.clearing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nz.ac.ledgercore.clearing.ClearingDtos.Callback;
import nz.ac.ledgercore.ledger.InsufficientLedgerBalanceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClearingReserveFailureIntegrationTests {

    private static final Pattern TOKEN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern ID = Pattern.compile("\\\"id\\\":\\\"([0-9a-f-]{36})\\\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClearingCallbackService callbacks;

    @MockitoSpyBean
    ClearingFinancialEffectProcessor effects;

    @Test
    void insufficientReserveResultKeepsObligationAndExactDuplicateCanRecover() throws Exception {
        Session admin = new Session();
        admin.register("Reserve Test Admin");
        HttpResponse<String> organization = admin.call("POST", "/api/organizations",
                "{\"displayName\":\"Reserve Test Company\"}", true);
        assertThat(organization.statusCode()).isEqualTo(201);
        String organizationId = capture(ID, organization.body());
        HttpResponse<String> account = admin.call("POST", "/api/organizations/" + organizationId + "/accounts",
                "{\"displayName\":\"Reserve Test Account\"}", true);
        assertThat(account.statusCode()).isEqualTo(201);
        String accountId = capture(ID, account.body());

        Session maker = new Session();
        maker.register("Reserve Test Maker");
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + maker.email + "\",\"roles\":[\"PAYMENT_MAKER\"]}", true)
                .statusCode()).isEqualTo(201);

        Session approver = new Session();
        approver.register("Reserve Test Approver");
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + approver.email + "\",\"roles\":[\"PAYMENT_APPROVER\"]}", true)
                .statusCode()).isEqualTo(201);

        String paymentRequest = "{\"sourceAccountId\":\"" + accountId
                + "\",\"beneficiaryName\":\"Test Supplier\",\"beneficiaryAccountReference\":\"NZ-RESERVE-1\","
                + "\"beneficiaryRoutingCode\":\"SIM-SETTLE\",\"amount\":\"5.00\"}";
        HttpResponse<String> draft = maker.call("POST", "/api/organizations/" + organizationId + "/payments",
                paymentRequest, true);
        assertThat(draft.statusCode()).isEqualTo(201);
        String paymentId = capture(ID, draft.body());
        assertThat(maker.call("POST", "/api/payments/" + paymentId + "/submit", null, true).statusCode())
                .isEqualTo(200);
        assertThat(approver.call("POST", "/api/payments/" + paymentId + "/approve", null, true).statusCode())
                .isEqualTo(200);

        UUID paymentUuid = UUID.fromString(paymentId);
        String digest = jdbc.queryForObject("SELECT intent_digest FROM corporate_payment_intents WHERE id = ?",
                String.class, paymentUuid).trim();
        Callback callback = new Callback(UUID.randomUUID(), paymentUuid, paymentUuid, digest, 1, "SETTLED",
                "SIM-SETTLED-RESERVE-RETRY", Instant.now());
        byte[] rawBody = JsonMapper.builder().build().writeValueAsBytes(callback);
        Long reserveBefore = jdbc.queryForObject("""
                SELECT balance_minor FROM account_balance_projection
                WHERE ledger_account_id = '00000000-0000-5000-8000-000000000001'
                """, Long.class);

        // Inject the transient insufficient-balance result before any settlement write; then retry the same callback.
        doThrow(new InsufficientLedgerBalanceException()).doCallRealMethod().when(effects).apply(paymentUuid);
        assertThat(callbacks.receive(callback, rawBody).result()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?",
                String.class, paymentUuid)).isEqualTo("PENDING_CLEARING");
        assertThat(jdbc.queryForObject("SELECT external_status FROM corporate_payment_intents WHERE id = ?",
                String.class, paymentUuid)).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE reference = ?",
                Integer.class, "corporate-payment:" + paymentId + ":settlement")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT balance_minor FROM account_balance_projection
                WHERE ledger_account_id = '00000000-0000-5000-8000-000000000001'
                """, Long.class)).isEqualTo(reserveBefore);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reconciliation_exceptions
                WHERE operation_id = ? AND category = 'CLEARING_RESERVE_INSUFFICIENT'
                  AND severity = 'CRITICAL' AND status = 'OPEN'
                """, Integer.class, paymentUuid)).isEqualTo(1);

        assertThat(callbacks.receive(callback, rawBody).result()).isEqualTo("DUPLICATE");
        assertThat(jdbc.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?",
                String.class, paymentUuid)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE reference = ?",
                Integer.class, "corporate-payment:" + paymentId + ":settlement")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT balance_minor FROM account_balance_projection
                WHERE ledger_account_id = '00000000-0000-5000-8000-000000000001'
                """, Long.class)).isEqualTo(reserveBefore - 500L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reconciliation_exceptions
                WHERE operation_id = ? AND category = 'CLEARING_RESERVE_INSUFFICIENT' AND status = 'OPEN'
                """, Integer.class, paymentUuid)).isEqualTo(1);
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

        private void register(String displayName) throws Exception {
            refreshCsrf();
            email = "reserve-" + UUID.randomUUID() + "@example.test";
            HttpResponse<String> response = call("POST", "/api/auth/register",
                    "{\"email\":\"" + email + "\",\"password\":\"Reserve-Test-Password!\",\"displayName\":\""
                            + displayName + "\"}", true);
            assertThat(response.statusCode()).isEqualTo(201);
        }

        private void refreshCsrf() throws Exception {
            HttpResponse<String> response = call("GET", "/api/auth/csrf", null, false);
            assertThat(response.statusCode()).isEqualTo(200);
            csrfToken = capture(TOKEN, response.body());
        }

        private HttpResponse<String> call(String method, String path, String body, boolean withCsrf) throws Exception {
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
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
