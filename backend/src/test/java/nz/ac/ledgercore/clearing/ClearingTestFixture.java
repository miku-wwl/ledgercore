package nz.ac.ledgercore.clearing;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Real-HTTP corporate payment fixture for the Round 4 clearing tests: actual sessions, CSRF, roles and approvals. */
final class ClearingTestFixture {

    static final UUID RESERVE = UUID.fromString("00000000-0000-5000-8000-000000000001");
    static final UUID PAYABLE = UUID.fromString("00000000-0000-5000-8000-000000000002");
    static final UUID TREASURY = UUID.fromString("00000000-0000-5000-8000-000000000003");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int port;
    private final JdbcTemplate jdbc;
    final Session admin;
    final Session maker;
    final Session approver;
    final Session operations;
    final UUID organizationId;
    final UUID accountId;

    ClearingTestFixture(int port, JdbcTemplate jdbc, String label) throws Exception {
        this.port = port;
        this.jdbc = jdbc;
        admin = new Session(label + "-admin");
        HttpResponse<String> organization = admin.call("POST", "/api/organizations",
                "{\"displayName\":\"" + label + " Company\"}");
        assertThat(organization.statusCode()).isEqualTo(201);
        organizationId = UUID.fromString(capture(ID, organization.body()));
        HttpResponse<String> account = admin.call("POST", "/api/organizations/" + organizationId + "/accounts",
                "{\"displayName\":\"" + label + " Operating\"}");
        assertThat(account.statusCode()).isEqualTo(201);
        accountId = UUID.fromString(capture(ID, account.body()));
        maker = member(label + "-maker", "PAYMENT_MAKER");
        approver = member(label + "-approver", "PAYMENT_APPROVER");
        operations = member(label + "-ops", "OPERATIONS");
    }

    private Session member(String label, String role) throws Exception {
        Session session = new Session(label);
        assertThat(admin.call("POST", "/api/organizations/" + organizationId + "/members",
                "{\"email\":\"" + session.email + "\",\"roles\":[\"" + role + "\"]}").statusCode()).isEqualTo(201);
        return session;
    }

    /** Creates, submits and independently approves one payment: the approval books deposit -> clearing payable. */
    UUID approvedPayment(String amount, String routingCode) throws Exception {
        HttpResponse<String> draft = maker.call("POST", "/api/organizations/" + organizationId + "/payments",
                "{\"sourceAccountId\":\"" + accountId + "\",\"beneficiaryName\":\"Synthetic Supplier\","
                        + "\"beneficiaryAccountReference\":\"NZ-R4-" + UUID.randomUUID().toString().substring(0, 8)
                        + "\",\"beneficiaryRoutingCode\":\"" + routingCode + "\",\"amount\":\"" + amount + "\"}");
        assertThat(draft.statusCode()).isEqualTo(201);
        UUID paymentId = UUID.fromString(capture(ID, draft.body()));
        assertThat(maker.call("POST", "/api/payments/" + paymentId + "/submit", null).statusCode()).isEqualTo(200);
        HttpResponse<String> approved = approver.call("POST", "/api/payments/" + paymentId + "/approve", null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        return paymentId;
    }

    Callback callback(UUID paymentId, long sequence, String status) {
        String digest = jdbc.queryForObject("SELECT intent_digest FROM corporate_payment_intents WHERE id = ?",
                String.class, paymentId).trim();
        return new Callback(UUID.randomUUID(), paymentId, paymentId, digest, sequence, status,
                "SIM-R4-" + paymentId.toString().substring(0, 8), Instant.now());
    }

    static byte[] bytes(Callback callback) {
        return JSON.writeValueAsBytes(callback);
    }

    long ledgerMinor(UUID ledgerAccountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0)
                FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id WHERE la.id = ?
                """, Long.class, ledgerAccountId);
    }

    long projectionMinor(UUID ledgerAccountId) {
        return jdbc.queryForObject("SELECT balance_minor FROM account_balance_projection WHERE ledger_account_id = ?",
                Long.class, ledgerAccountId);
    }

    long sourceDepositMinor() {
        UUID ledger = jdbc.queryForObject(
                "SELECT id FROM ledger_accounts WHERE customer_account_id = ? AND kind = 'CUSTOMER_DEPOSIT'",
                UUID.class, accountId);
        return ledgerMinor(ledger);
    }

    int journals(UUID paymentId, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM journal_entries WHERE reference = ?", Integer.class,
                "corporate-payment:" + paymentId + ":" + action);
    }

    String paymentStatus(UUID paymentId) {
        return jdbc.queryForObject("SELECT status || '/' || COALESCE(external_status, '-') FROM corporate_payment_intents WHERE id = ?",
                String.class, paymentId);
    }

    /** Debit-normal assets equal credit-normal liabilities across the whole ledger (no money created or lost). */
    boolean balanceSheetBalances() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE WHEN la.normal_side = 'DEBIT' AND p.side = 'DEBIT' THEN p.amount_minor
                                         WHEN la.normal_side = 'DEBIT' THEN -p.amount_minor ELSE 0 END), 0)
                     = COALESCE(sum(CASE WHEN la.normal_side = 'CREDIT' AND p.side = 'CREDIT' THEN p.amount_minor
                                         WHEN la.normal_side = 'CREDIT' THEN -p.amount_minor ELSE 0 END), 0)
                FROM postings p JOIN ledger_accounts la ON la.id = p.ledger_account_id
                """, Boolean.class));
    }

    static String capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertThat(matcher.find()).as(text).isTrue();
        return matcher.group(1);
    }

    static String capture(String jsonField, String text) {
        return capture(Pattern.compile("\"" + jsonField + "\":\"([^\"]+)\""), text);
    }

    final class Session {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        final String email;
        final UUID customerId;

        Session(String label) throws Exception {
            String slug = label.toLowerCase().replaceAll("[^a-z0-9]", "");
            email = "r4-" + slug.substring(0, Math.min(24, slug.length())) + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.test";
            HttpResponse<String> response = call("POST", "/api/auth/register", "{\"email\":\"" + email
                    + "\",\"password\":\"Round4-Test-Password!\",\"displayName\":\"" + label + "\"}");
            assertThat(response.statusCode()).isEqualTo(201);
            customerId = UUID.fromString(capture(ID, response.body()));
        }

        HttpResponse<String> call(String method, String path, String body) throws Exception {
            HttpResponse<String> csrf = send("GET", "/api/auth/csrf", null, null);
            String token = capture(TOKEN, csrf.body());
            return send(method, path, body, token);
        }

        private HttpResponse<String> send(String method, String path, String body, String csrfToken) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            else {
                request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            if (csrfToken != null) {
                request.header("X-CSRF-TOKEN", csrfToken);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
