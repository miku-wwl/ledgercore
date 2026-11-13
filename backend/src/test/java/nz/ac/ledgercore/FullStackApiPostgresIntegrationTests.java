package nz.ac.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FullStackApiPostgresIntegrationTests {

	private static final Pattern TOKEN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
	private static final Pattern ACCOUNT_ID = Pattern.compile("\\\"id\\\":\\\"([0-9a-f-]{36})\\\"");

	@Value("${local.server.port}")
	int port;

	@Test
	void sessionsCsrfOwnershipTransfersReceiptsHistoryAndReconciliationUsePostgres() throws Exception {
		Session alice = new Session();
		alice.refreshCsrf();
		String aliceEmail = "p4-alice-" + UUID.randomUUID() + "@example.test";
		HttpResponse<String> registration = alice.call("POST", "/api/auth/register",
				"{\"email\":\"" + aliceEmail + "\",\"password\":\"P4IntegrationPassword!\",\"displayName\":\"Alice Test\"}", true);
		assertThat(registration.statusCode()).isEqualTo(201);
		assertThat(alice.call("GET", "/api/auth/me", null, false).statusCode()).isEqualTo(200);

		String aliceSource = createAccount(alice, "Alice spending");
		String aliceSecond = createAccount(alice, "Alice reserve");
		assertThat(alice.call("POST", "/api/accounts", "{", true).statusCode()).isEqualTo(400);
		assertThat(alice.call("GET", "/api/accounts/not-a-uuid", null, false).statusCode()).isEqualTo(400);
		HttpResponse<String> invalidCursor = alice.call("GET",
				"/api/accounts/" + aliceSource + "/transactions?cursor=private-cursor-value", null, false);
		assertThat(invalidCursor.statusCode()).isEqualTo(400);
		assertThat(invalidCursor.body()).contains("One or more request values are invalid.")
			.doesNotContain("private-cursor-value");
		assertThat(alice.call("POST", "/api/accounts", "{\"displayName\":\"CSRF missing\"}", false).statusCode())
				.isEqualTo(403);

		Session bob = new Session();
		bob.refreshCsrf();
		String bobEmail = "p4-bob-" + UUID.randomUUID() + "@example.test";
		assertThat(bob.call("POST", "/api/auth/register",
				"{\"email\":\"" + bobEmail + "\",\"password\":\"P4IntegrationPassword!\",\"displayName\":\"Bob Test\"}",
				true).statusCode()).isEqualTo(201);
		String bobAccount = createAccount(bob, "Bob account");
		assertThat(bob.call("GET", "/api/accounts/" + aliceSource, null, false).statusCode()).isEqualTo(404);

		String transferIntent = "{\"sourceAccountId\":\"" + aliceSource + "\",\"targetAccountId\":\"" + bobAccount
				+ "\",\"amount\":\"10.00\"}";
		assertThat(alice.call("POST", "/api/transfers", transferIntent, true).statusCode()).isEqualTo(400);
		HttpResponse<String> first = alice.call("POST", "/api/transfers", transferIntent, true, "p4-transfer-key-1");
		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(first.body()).contains("\"status\":\"POSTED\"", "\"amount\":\"10.00\"");
		HttpResponse<String> replay = alice.call("POST", "/api/transfers", transferIntent, true, "p4-transfer-key-1");
		assertThat(replay.statusCode()).isEqualTo(200);
		assertThat(replay.body()).isEqualTo(first.body());
		assertThat(alice.call("POST", "/api/transfers", transferIntent.replace("10.00", "9.00"), true,
				"p4-transfer-key-1").statusCode()).isEqualTo(409);
		assertThat(alice.call("POST", "/api/transfers", transferIntent.replace("10.00", "2000.00"), true,
				"p4-transfer-key-insufficient").statusCode()).isEqualTo(422);

		assertThat(alice.call("GET", "/api/accounts/" + aliceSource, null, false).body()).contains("\"balance\":\"990.00\"");
		assertThat(bob.call("GET", "/api/accounts/" + bobAccount, null, false).body()).contains("\"balance\":\"1010.00\"");
		HttpResponse<String> aliceHistory = alice.call("GET", "/api/accounts/" + aliceSource + "/transactions?limit=1", null,
				false);
		assertThat(aliceHistory.statusCode()).isEqualTo(200);
		assertThat(aliceHistory.body()).contains("OUTGOING", "Bob account", "10.00");
		String transferId = capture(Pattern.compile("\\\"id\\\":\\\"([0-9a-f-]{36})\\\""), aliceHistory.body());
		HttpResponse<String> ledgerDetail = alice.call("GET",
				"/api/accounts/" + aliceSource + "/transactions/" + transferId, null, false);
		assertThat(ledgerDetail.statusCode()).isEqualTo(200);
		assertThat(ledgerDetail.body()).contains("This account", "Counterparty", "DEBIT", "CREDIT");
		assertThat(ledgerDetail.body()).doesNotContain(aliceSecond, bobAccount);
		assertThat(alice.call("GET", "/api/accounts/" + aliceSource + "/reconciliation", null, false).body())
				.contains("\"matches\":true", "990.00");

		assertThat(alice.call("POST", "/api/auth/logout", "{}", true).statusCode()).isEqualTo(204);
		assertThat(alice.call("GET", "/api/auth/me", null, false).statusCode()).isEqualTo(401);
	}

	private String createAccount(Session session, String displayName) throws Exception {
		HttpResponse<String> response = session.call("POST", "/api/accounts", "{\"displayName\":\"" + displayName + "\"}",
				true);
		assertThat(response.statusCode()).isEqualTo(201);
		return capture(ACCOUNT_ID, response.body());
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

		private void refreshCsrf() throws Exception {
			HttpResponse<String> response = call("GET", "/api/auth/csrf", null, false);
			assertThat(response.statusCode()).isEqualTo(200);
			csrfToken = capture(TOKEN, response.body());
		}

		private HttpResponse<String> call(String method, String path, String body, boolean withCsrf) throws Exception {
			return call(method, path, body, withCsrf, null);
		}

		private HttpResponse<String> call(String method, String path, String body, boolean withCsrf, String idempotencyKey)
				throws Exception {
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
