package nz.ac.ledgercore.reconciliation;

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

import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.identity.IdentityService;
import nz.ac.ledgercore.organizations.OrganizationRole;
import nz.ac.ledgercore.organizations.OrganizationService;
import nz.ac.ledgercore.payroll.PayrollExecutionService;
import nz.ac.ledgercore.payroll.PayrollService;
import nz.ac.ledgercore.payroll.PayrollViews;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationPostgresIntegrationTests {

	private static final String PASSWORD = "Recon-Integration-Password!";
	private static final Pattern TOKEN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
	private static final Pattern ID = Pattern.compile("\\\"id\\\":\\\"([0-9a-f-]{36})\\\"");
	private static final Pattern EXCEPTION_ID = Pattern.compile("\\\"exceptionId\\\":\\\"([0-9a-f-]{36})\\\"");

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	IdentityService identities;

	@Autowired
	AccountService accounts;

	@Autowired
	OrganizationService organizations;

	@Autowired
	PayrollService payroll;

	@Autowired
	PayrollExecutionService payrollExecution;

	@Test
	void reconcilesHealthyOrganizationThenDetectsAndResolvesProjectionAnomaly() throws Exception {
		Session admin = new Session();
		UUID adminId = UUID.fromString(admin.register("Reconciliation Admin"));
		HttpResponse<String> organizationResponse = admin.call("POST", "/api/organizations",
				"{\"displayName\":\"Recon " + UUID.randomUUID() + "\"}", true);
		assertThat(organizationResponse.statusCode()).isEqualTo(201);
		UUID organizationId = UUID.fromString(capture(ID, organizationResponse.body()));

		HttpResponse<String> accountResponse = admin.call("POST", "/api/organizations/" + organizationId + "/accounts",
				"{\"displayName\":\"Reconciliation fixture account\"}", true);
		assertThat(accountResponse.statusCode()).isEqualTo(201);
		UUID payerAccountId = UUID.fromString(capture(ID, accountResponse.body()));

		Session operator = new Session();
		UUID operatorId = UUID.fromString(operator.register("Reconciliation Operator"));
		HttpResponse<String> operatorGrant = admin.call("POST", "/api/organizations/" + organizationId + "/members",
				"{\"email\":\"" + operator.email + "\",\"roles\":[\"OPERATIONS\",\"PAYROLL_APPROVER\"]}", true);
		assertThat(operatorGrant.statusCode()).isEqualTo(201);

		IdentityService.CustomerProfile maker = identities.register("reconciliation-maker-" + UUID.randomUUID()
				+ "@example.test", PASSWORD, "Reconciliation Payroll Maker");
		organizations.addMember(adminId, organizationId, maker.email(), java.util.List.of(OrganizationRole.PAYROLL_MAKER));
		IdentityService.CustomerProfile employee = identities.register("reconciliation-employee-" + UUID.randomUUID()
				+ "@example.test", PASSWORD, "Reconciliation Payroll Employee");
		UUID employeeAccountId = accounts.openAccount(employee.id(), "E7 reconciliation payroll employee");
		PayrollViews.BatchView draft = payroll.createDraft(maker.id(), organizationId,
				"E7 healthy payroll " + UUID.randomUUID(), payerAccountId);
		draft = payroll.addItems(maker.id(), draft.id(), java.util.List.of(new PayrollService.NewPayrollItem(
				"EMP-E7", "E7 Payroll Employee", employeeAccountId, "10.00")));
		payroll.submit(maker.id(), draft.id());
		payroll.approve(operatorId, draft.id());
		payroll.startProcessing(draft.id(), operatorId);
		PayrollViews.BatchView completedPayroll = payrollExecution.execute(operatorId, draft.id(), 10);
		assertThat(completedPayroll.status()).isEqualTo("COMPLETED");
		assertThat(completedPayroll.succeededCount()).isEqualTo(1);
		assertThat(completedPayroll.pendingCount()).isZero();

		Session viewer = new Session();
		viewer.register("Reconciliation Viewer");
		HttpResponse<String> viewerGrant = admin.call("POST", "/api/organizations/" + organizationId + "/members",
				"{\"email\":\"" + viewer.email + "\",\"roles\":[\"FINANCE_VIEWER\"]}", true);
		assertThat(viewerGrant.statusCode()).isEqualTo(201);

		String basePath = "/api/organizations/" + organizationId + "/operations/reconciliation";
		HttpResponse<String> healthy = operator.call("POST", basePath + "/runs", null, true);
		assertThat(healthy.statusCode()).isEqualTo(200);
		assertThat(healthy.body()).contains("\"findingCount\":0");
		assertThat(healthy.body()).contains("CLEARING_SIMULATOR_NOT_CONFIGURED");

		UUID ledgerAccountId = jdbc.queryForObject("""
				SELECT la.id FROM accounts a JOIN ledger_accounts la ON la.customer_account_id = a.id
				WHERE a.owner_organization_id = ? AND la.kind = 'CUSTOMER_DEPOSIT'
				""", UUID.class, organizationId);
		long journalCountBefore = jdbc.queryForObject("""
				SELECT count(*) FROM journal_entries je JOIN postings p ON p.journal_entry_id = je.id
				WHERE p.ledger_account_id = ?
				""", Long.class, ledgerAccountId);
		int changed = jdbc.update("UPDATE account_balance_projection SET balance_minor = balance_minor + 1 "
				+ "WHERE ledger_account_id = ?", ledgerAccountId);
		assertThat(changed).isEqualTo(1);

		HttpResponse<String> anomalous = operator.call("POST", basePath + "/runs", null, true);
		assertThat(anomalous.statusCode()).isEqualTo(200);
		assertThat(anomalous.body()).contains("\"findingCount\":1", "LEDGER_PROJECTION_MISMATCH",
				"\"ledgerMinor\":99000", "\"projectionMinor\":99001",
				"Rebuild the account projection from immutable postings; keep journal entries untouched.");
		UUID exceptionId = UUID.fromString(capture(EXCEPTION_ID, anomalous.body()));
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM reconciliation_exceptions
				WHERE id = ? AND organization_id = ? AND category = 'LEDGER_PROJECTION_MISMATCH'
				  AND expected_facts ->> 'resourceId' = ?
				""", Integer.class, exceptionId, organizationId,
				ledgerCustomerAccountId(ledgerAccountId).toString())).isEqualTo(1);

		assertThat(viewer.call("GET", basePath + "/exceptions", null, false).statusCode()).isEqualTo(403);
		assertThat(viewer.call("POST", basePath + "/runs", null, true).statusCode()).isEqualTo(403);
		assertThat(viewer.call("POST", basePath + "/exceptions/" + exceptionId + "/assign",
				"{\"assignedToCustomerId\":\"" + operatorId + "\"}", true).statusCode()).isEqualTo(403);
		assertThat(viewer.call("POST", basePath + "/exceptions/" + exceptionId + "/resolve",
				"{\"resolution\":\"Unauthorized resolution attempt\",\"correctionJournalEntryId\":null}", true)
				.statusCode()).isEqualTo(403);
		Session outsider = new Session();
		outsider.register("Reconciliation Outsider");
		assertThat(outsider.call("GET", basePath + "/exceptions", null, false).statusCode()).isEqualTo(404);
		assertThat(outsider.call("POST", basePath + "/runs", null, true).statusCode()).isEqualTo(404);

		HttpResponse<String> listed = operator.call("GET", basePath + "/exceptions", null, false);
		assertThat(listed.statusCode()).isEqualTo(200);
		assertThat(listed.body()).contains(exceptionId.toString(), "OPEN", "LEDGER_PROJECTION_MISMATCH");

		HttpResponse<String> assigned = operator.call("POST", basePath + "/exceptions/" + exceptionId + "/assign",
				"{\"assignedToCustomerId\":\"" + operatorId + "\"}", true);
		assertThat(assigned.statusCode()).isEqualTo(200);
		assertThat(assigned.body()).contains("\"status\":\"ASSIGNED\"", operatorId.toString());
		HttpResponse<String> assignedFilter = operator.call("GET", basePath + "/exceptions?status=ASSIGNED", null, false);
		assertThat(assignedFilter.statusCode()).isEqualTo(200);
		assertThat(assignedFilter.body()).contains(exceptionId.toString(), "ASSIGNED");
		HttpResponse<String> openFilter = operator.call("GET", basePath + "/exceptions?status=OPEN", null, false);
		assertThat(openFilter.statusCode()).isEqualTo(200);
		assertThat(openFilter.body()).isEqualTo("[]");
		String originalExpectedFacts = jdbc.queryForObject(
				"SELECT expected_facts::text FROM reconciliation_exceptions WHERE id = ?", String.class, exceptionId);
		String originalObservedFacts = jdbc.queryForObject(
				"SELECT observed_facts::text FROM reconciliation_exceptions WHERE id = ?", String.class, exceptionId);
		Long authoritativeBalance = jdbc.queryForObject("""
				SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0)
				FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id
				WHERE la.id = ? GROUP BY la.id, la.normal_side
				""", Long.class, ledgerAccountId);
		jdbc.update("UPDATE account_balance_projection SET balance_minor = ? WHERE ledger_account_id = ?",
				authoritativeBalance, ledgerAccountId);
		HttpResponse<String> repaired = operator.call("POST", basePath + "/runs", null, true);
		assertThat(repaired.statusCode()).isEqualTo(200);
		assertThat(repaired.body()).contains("\"findingCount\":0");

		HttpResponse<String> resolved = operator.call("POST", basePath + "/exceptions/" + exceptionId + "/resolve",
				"{\"resolution\":\"Projection rebuilt from the authoritative posting sum; ledger history was not changed.\","
						+ "\"correctionJournalEntryId\":null}", true);
		assertThat(resolved.statusCode()).isEqualTo(200);
		assertThat(resolved.body()).contains("\"status\":\"RESOLVED\"", operatorId.toString());
		assertThat(jdbc.queryForObject("SELECT expected_facts::text FROM reconciliation_exceptions WHERE id = ?",
				String.class, exceptionId)).isEqualTo(originalExpectedFacts);
		assertThat(jdbc.queryForObject("SELECT observed_facts::text FROM reconciliation_exceptions WHERE id = ?",
				String.class, exceptionId)).isEqualTo(originalObservedFacts);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_events WHERE organization_id = ? AND resource_id = ?
				  AND actor_customer_id = ? AND action IN (
				      'RECONCILIATION_EXCEPTION_ASSIGNED', 'RECONCILIATION_EXCEPTION_RESOLVED')
				""", Integer.class, organizationId, exceptionId, operatorId)).isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM journal_entries je JOIN postings p ON p.journal_entry_id = je.id
				WHERE p.ledger_account_id = ?
				""", Long.class, ledgerAccountId)).isEqualTo(journalCountBefore);
		assertThat(jdbc.queryForObject("SELECT balance_minor FROM account_balance_projection WHERE ledger_account_id = ?",
				Long.class, ledgerAccountId)).isEqualTo(99_000L);
	}

	private UUID ledgerCustomerAccountId(UUID ledgerAccountId) {
		return jdbc.queryForObject("SELECT customer_account_id FROM ledger_accounts WHERE id = ?", UUID.class,
				ledgerAccountId);
	}

	private static String capture(Pattern pattern, String value) {
		Matcher matcher = pattern.matcher(value);
		if (!matcher.find()) {
			throw new AssertionError("Expected a matching identifier in response: " + value);
		}
		return matcher.group(1);
	}

	private final class Session {
		private final HttpClient client = HttpClient.newBuilder()
				.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
		private String csrfToken;
		private String email;

		private String register(String displayName) throws Exception {
			refreshCsrf();
			email = "reconciliation-" + UUID.randomUUID() + "@example.test";
			HttpResponse<String> response = call("POST", "/api/auth/register",
					"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"displayName\":\""
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
