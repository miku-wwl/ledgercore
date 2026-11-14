package nz.ac.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import nz.ac.ledgercore.api.E6FaultInjectionHook;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerCoreApplicationTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	ApplicationContext applicationContext;

	@Value("${local.server.port}")
	int port;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	void healthEndpointChecksTheRealPostgresConnectionAndEchoesCorrelationId() throws Exception {
		HttpResponse<String> response = get("/actuator/health", "p1-health-check-01");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
		assertThat(response.headers().firstValue(CorrelationIdFilter.HEADER_NAME)).contains("p1-health-check-01");
	}

	@Test
	void faultInjectionHookIsAbsentWithoutTheDedicatedE6Profile() {
		assertThat(applicationContext.getBeansOfType(E6FaultInjectionHook.class)).isEmpty();
	}

	@Test
	void missingCorrelationIdIsGenerated() throws Exception {
		HttpResponse<String> response = get("/actuator/health", null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue(CorrelationIdFilter.HEADER_NAME)).isPresent();
	}

	@Test
	void unauthenticatedApiRequestReturnsAStandardProblemResponse() throws Exception {
		HttpResponse<String> response = get("/api/private", "p1-auth-check-01");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Content-Type"))
				.hasValueSatisfying(contentType -> assertThat(contentType).startsWith("application/problem+json"));
		assertThat(response.body()).contains("\"status\":401", "\"correlationId\":\"p1-auth-check-01\"");
	}

	@Test
	void openApiDocumentIsAvailableAndIdentifiesTheApplication() throws Exception {
		HttpResponse<String> response = get("/v3/api-docs", null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("LedgerCore API");
	}

	@Test
	void flywayCreatedTheCustomerAndAccountTablesInPostgres() {
		Integer customerTableCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'customers'",
				Integer.class);
		Integer accountTableCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'accounts'",
				Integer.class);
		Integer appliedMigrationCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
				Integer.class);

		assertThat(customerTableCount).isEqualTo(1);
		assertThat(accountTableCount).isEqualTo(1);
		assertThat(appliedMigrationCount).isEqualTo(1);
	}

	private HttpResponse<String> get(String path, String correlationId) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
		if (correlationId != null) {
			request.header(CorrelationIdFilter.HEADER_NAME, correlationId);
		}
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
