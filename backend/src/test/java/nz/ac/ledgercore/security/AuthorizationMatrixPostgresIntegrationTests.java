package nz.ac.ledgercore.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round 4 / E8: executed role x route x object authorization matrix over real HTTP sessions, CSRF and PostgreSQL. Every
 * cell is a real request. Non-members of the organization must receive 404 (concealment), members without the role 403,
 * anonymous callers 401. After every denied mutating request an independent SQL fingerprint of journals, postings,
 * payments, payroll and memberships must be unchanged. The machine-readable result is written to
 * target/e8-authorization-matrix.json.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationMatrixPostgresIntegrationTests {

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");
    private static final List<String> PRINCIPALS = List.of("ANONYMOUS", "PERSONAL_CUSTOMER", "ORG_ADMIN", "PAYMENT_MAKER",
            "PAYMENT_APPROVER", "PAYROLL_MAKER", "PAYROLL_APPROVER", "FINANCE_VIEWER", "OPERATIONS", "SUSPENDED_MEMBER",
            "CROSS_TENANT_ADMIN", "SUSPENDED_ORG_ADMIN");
    private static final Set<String> NON_MEMBERS = Set.of("PERSONAL_CUSTOMER", "SUSPENDED_MEMBER", "CROSS_TENANT_ADMIN",
            "SUSPENDED_ORG_ADMIN");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final List<Map<String, Object>> cells = new ArrayList<>();
    private UUID org;
    private UUID orgAccount;
    private UUID personalSource;
    private UUID personalTarget;

    @Test
    void everyRoleRouteAndObjectCombinationIsEnforcedWithoutSideEffects() throws Exception {
        fixture();
        List<Route> routes = routes();
        for (Route route : routes) {
            Supplier<String> target = route.target();
            String path = target.get();
            List<String> order = new ArrayList<>(PRINCIPALS);
            order.removeAll(route.allowed());
            order.addAll(route.allowed().stream().filter(PRINCIPALS::contains).toList());
            for (String principal : order) {
                if (route.mutating() && route.allowed().contains(principal) && route.freshTargetPerAllowed()) {
                    path = target.get();
                }
                String before = fingerprint();
                HttpResponse<String> response = call(principal, route.method(), path, route.body().get());
                int expected = expected(route, principal);
                boolean unchanged = true;
                if (route.mutating() && expected >= 400) {
                    unchanged = before.equals(fingerprint());
                }
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("routeId", route.id());
                cell.put("method", route.method());
                cell.put("route", route.template());
                cell.put("principal", principal);
                cell.put("expected", expected);
                cell.put("actual", response.statusCode());
                cell.put("mutating", route.mutating());
                cell.put("deniedWithoutSideEffects", route.mutating() && expected >= 400 ? unchanged : null);
                cell.put("pass", response.statusCode() == expected && unchanged);
                cells.add(cell);
            }
        }
        Path output = Path.of("target", "e8-authorization-matrix.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("routes", routes.size(), "principals", PRINCIPALS, "cells", cells.size(),
                        "failures", cells.stream().filter(cell -> !(Boolean) cell.get("pass")).count(), "matrix", cells)));
        List<Map<String, Object>> failures = cells.stream().filter(cell -> !(Boolean) cell.get("pass")).toList();
        assertThat(failures).as("authorization matrix failures").isEmpty();
        assertThat(cells).hasSizeGreaterThanOrEqualTo(routes.size() * PRINCIPALS.size());
    }

    @Test
    void stateChangingRequestsWithoutCsrfAreRejectedForEveryAuthenticatedPrincipalClass() throws Exception {
        fixture();
        for (String principal : List.of("PERSONAL_CUSTOMER", "ORG_ADMIN", "PAYMENT_MAKER", "OPERATIONS")) {
            String before = fingerprint();
            HttpResponse<String> response = sessions.get(principal).send("POST", "/api/organizations/" + org + "/payments",
                    paymentBody(), null);
            assertThat(response.statusCode()).as(principal).isEqualTo(403);
            HttpResponse<String> forged = sessions.get(principal).send("POST", "/api/organizations/" + org + "/payments",
                    paymentBody(), "forged-" + UUID.randomUUID());
            assertThat(forged.statusCode()).as(principal + " forged token").isEqualTo(403);
            assertThat(fingerprint()).isEqualTo(before);
        }
    }

    @Test
    void aMemberHoldingBothMakerAndApproverRolesCannotApproveTheirOwnPaymentOrPayroll() throws Exception {
        fixture();
        Session dual = new Session("DUAL_ROLE");
        assertThat(sessions.get("ORG_ADMIN").call("POST", "/api/organizations/" + org + "/members", "{\"email\":\""
                + dual.email + "\",\"roles\":[\"PAYMENT_MAKER\",\"PAYMENT_APPROVER\",\"PAYROLL_MAKER\",\"PAYROLL_APPROVER\"]}")
                .statusCode()).isEqualTo(201);
        UUID payment = UUID.fromString(capture(ID, dual.call("POST", "/api/organizations/" + org + "/payments",
                paymentBody()).body()));
        assertThat(dual.call("POST", "/api/payments/" + payment + "/submit", null).statusCode()).isEqualTo(200);
        UUID batch = UUID.fromString(capture(ID, dual.call("POST", "/api/organizations/" + org + "/payroll/batches",
                "{\"name\":\"Dual role batch\",\"payerAccountId\":\"" + orgAccount + "\"}").body()));
        assertThat(dual.call("POST", "/api/payroll/batches/" + batch + "/items", itemsBody()).statusCode()).isEqualTo(200);
        assertThat(dual.call("POST", "/api/payroll/batches/" + batch + "/submit", null).statusCode()).isEqualTo(200);
        String before = fingerprint();

        assertThat(dual.call("POST", "/api/payments/" + payment + "/approve", null).statusCode()).isEqualTo(403);
        assertThat(dual.call("POST", "/api/payments/" + payment + "/reject", "{\"reason\":\"self\"}").statusCode())
                .isEqualTo(403);
        assertThat(dual.call("POST", "/api/payroll/batches/" + batch + "/approve", null).statusCode()).isEqualTo(403);
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(sessions.get("PAYMENT_APPROVER").call("POST", "/api/payments/" + payment + "/approve", null).statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT approved_by_customer_id FROM corporate_payment_intents WHERE id = ?",
                UUID.class, payment)).isEqualTo(sessions.get("PAYMENT_APPROVER").customerId);
    }

    @Test
    void anApproverWhoseRoleIsRevokedAfterSubmissionCanNoLongerBookThePayment() throws Exception {
        fixture();
        Session approver = new Session("LATE_APPROVER");
        assertThat(sessions.get("ORG_ADMIN").call("POST", "/api/organizations/" + org + "/members", "{\"email\":\""
                + approver.email + "\",\"roles\":[\"PAYMENT_APPROVER\",\"FINANCE_VIEWER\"]}").statusCode()).isEqualTo(201);
        UUID payment = payment("SUBMITTED");
        assertThat(sessions.get("ORG_ADMIN").call("DELETE", "/api/organizations/" + org + "/members/" + approver.customerId
                + "/roles/PAYMENT_APPROVER", null).statusCode()).isEqualTo(200);
        String before = fingerprint();
        assertThat(approver.call("POST", "/api/payments/" + payment + "/approve", null).statusCode()).isEqualTo(403);
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?", String.class, payment))
                .isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events WHERE organization_id = ? AND action = 'ORGANIZATION_ROLE_REVOKED'
                  AND actor_customer_id = ?
                """, Integer.class, org, sessions.get("ORG_ADMIN").customerId)).isPositive();
    }

    // ----- expectations -----

    private int expected(Route route, String principal) {
        if ("ANONYMOUS".equals(principal)) {
            return 401;
        }
        return switch (route.scope()) {
            case ANY_AUTHENTICATED -> route.allowedStatus();
            case OWNER -> route.allowed().contains(principal) ? route.allowedStatus()
                    : route.makerConflict() && Set.of("PAYMENT_MAKER", "PAYROLL_MAKER").contains(principal) ? 409 : 404;
            case ORGANIZATION -> NON_MEMBERS.contains(principal) ? 404
                    : route.allowed().contains(principal) ? route.allowedStatus() : 403;
            case OPERATIONS_ANY_ORG -> "OPERATIONS".equals(principal) ? route.allowedStatus() : 403;
        };
    }

    private List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        Set<String> members = Set.of("ORG_ADMIN", "PAYMENT_MAKER", "PAYMENT_APPROVER", "PAYROLL_MAKER", "PAYROLL_APPROVER",
                "FINANCE_VIEWER", "OPERATIONS");
        Set<String> everyone = Set.copyOf(PRINCIPALS);
        // Identity and personal banking
        routes.add(read("AUTH-ME", "/api/auth/me", Scope.ANY_AUTHENTICATED, everyone));
        routes.add(read("ORG-LIST", "/api/organizations", Scope.ANY_AUTHENTICATED, everyone));
        routes.add(read("PAY-RECENT", "/api/payments", Scope.ANY_AUTHENTICATED, everyone));
        routes.add(read("ACCOUNT-LIST", "/api/accounts", Scope.ANY_AUTHENTICATED, everyone));
        routes.add(write("ACCOUNT-OPEN", "POST", () -> "/api/accounts", () -> "{\"displayName\":\"Matrix personal\"}",
                Scope.ANY_AUTHENTICATED, everyone, 201));
        routes.add(read("ACCOUNT-GET", "/api/accounts/" + personalSource, Scope.OWNER, Set.of("PERSONAL_CUSTOMER")));
        routes.add(read("HISTORY-LIST", "/api/accounts/" + personalSource + "/transactions", Scope.OWNER,
                Set.of("PERSONAL_CUSTOMER")));
        routes.add(read("ACCOUNT-RECON", "/api/accounts/" + personalSource + "/reconciliation", Scope.OWNER,
                Set.of("PERSONAL_CUSTOMER")));
        routes.add(new Route("TRANSFER-PERSONAL", "POST", "/api/transfers", () -> "/api/transfers",
                () -> transferBody(personalSource, personalTarget), true, Scope.OWNER, Set.of("PERSONAL_CUSTOMER"), 200, false,
                false));
        // Policy B: no generic transfer may debit a corporate account; makers learn it needs approval (409).
        routes.add(new Route("TRANSFER-CORPORATE", "POST", "/api/transfers", () -> "/api/transfers",
                () -> transferBody(orgAccount, personalTarget), true, Scope.OWNER, Set.of(), 0, false, true));
        // Organization administration
        routes.add(read("ORG-ACCOUNTS", "/api/organizations/" + org + "/accounts", Scope.ORGANIZATION, members));
        routes.add(write("ORG-ACCOUNT-OPEN", "POST", () -> "/api/organizations/" + org + "/accounts",
                () -> "{\"displayName\":\"Matrix corporate\"}", Scope.ORGANIZATION, Set.of("ORG_ADMIN"), 201));
        routes.add(read("ORG-MEMBERS", "/api/organizations/" + org + "/members", Scope.ORGANIZATION, Set.of("ORG_ADMIN")));
        routes.add(write("ORG-MEMBER-ADD", "POST", () -> "/api/organizations/" + org + "/members",
                () -> "{\"email\":\"" + newCustomerEmail() + "\",\"roles\":[\"FINANCE_VIEWER\"]}", Scope.ORGANIZATION,
                Set.of("ORG_ADMIN"), 201));
        routes.add(fresh("ORG-ROLE-GRANT", "POST", () -> "/api/organizations/" + org + "/members/" + newMember()
                + "/roles", () -> "{\"role\":\"FINANCE_VIEWER\"}", Set.of("ORG_ADMIN"), 200));
        routes.add(fresh("ORG-ROLE-REVOKE", "DELETE", () -> "/api/organizations/" + org + "/members/" + newMember()
                + "/roles/PAYROLL_MAKER", () -> null, Set.of("ORG_ADMIN"), 200));
        routes.add(fresh("ORG-MEMBER-SUSPEND", "POST", () -> "/api/organizations/" + org + "/members/" + newMember()
                + "/suspend", () -> null, Set.of("ORG_ADMIN"), 200));
        routes.add(fresh("ORG-MEMBER-REACTIVATE", "POST", () -> "/api/organizations/" + org + "/members/"
                + newSuspendedMember() + "/reactivate", () -> null, Set.of("ORG_ADMIN"), 200));
        // Corporate payments (Policy B)
        routes.add(read("PAY-LIST", "/api/organizations/" + org + "/payments", Scope.ORGANIZATION, members));
        routes.add(write("PAY-CREATE", "POST", () -> "/api/organizations/" + org + "/payments", this::paymentBody,
                Scope.ORGANIZATION, Set.of("PAYMENT_MAKER"), 201));
        routes.add(new Route("PAY-GET", "GET", "/api/payments/{p}", () -> "/api/payments/" + payment("DRAFT"),
                () -> null, false, Scope.ORGANIZATION, members, 200, false, false));
        routes.add(fresh("PAY-SUBMIT", "POST", () -> "/api/payments/" + payment("DRAFT") + "/submit", () -> null,
                Set.of("PAYMENT_MAKER"), 200));
        routes.add(fresh("PAY-APPROVE", "POST", () -> "/api/payments/" + payment("SUBMITTED") + "/approve", () -> null,
                Set.of("PAYMENT_APPROVER"), 200));
        routes.add(fresh("PAY-REJECT", "POST", () -> "/api/payments/" + payment("SUBMITTED") + "/reject",
                () -> "{\"reason\":\"Matrix rejection\"}", Set.of("PAYMENT_APPROVER"), 200));
        // Payroll
        routes.add(read("PAYROLL-LIST", "/api/organizations/" + org + "/payroll/batches", Scope.ORGANIZATION, members));
        routes.add(write("PAYROLL-CREATE", "POST", () -> "/api/organizations/" + org + "/payroll/batches",
                () -> "{\"name\":\"Matrix payroll\",\"payerAccountId\":\"" + orgAccount + "\"}", Scope.ORGANIZATION,
                Set.of("PAYROLL_MAKER"), 201));
        routes.add(new Route("PAYROLL-GET", "GET", "/api/payroll/batches/{b}", () -> "/api/payroll/batches/" + batch("DRAFT"),
                () -> null, false, Scope.ORGANIZATION, members, 200, false, false));
        routes.add(new Route("PAYROLL-ITEMS", "GET", "/api/payroll/batches/{b}/items",
                () -> "/api/payroll/batches/" + batch("DRAFT") + "/items", () -> null, false, Scope.ORGANIZATION, members,
                200, false, false));
        routes.add(fresh("PAYROLL-ADD-ITEMS", "POST", () -> "/api/payroll/batches/" + batch("EMPTY") + "/items",
                this::itemsBody, Set.of("PAYROLL_MAKER"), 200));
        routes.add(fresh("PAYROLL-SUBMIT", "POST", () -> "/api/payroll/batches/" + batch("DRAFT") + "/submit", () -> null,
                Set.of("PAYROLL_MAKER"), 200));
        routes.add(fresh("PAYROLL-APPROVE", "POST", () -> "/api/payroll/batches/" + batch("SUBMITTED") + "/approve",
                () -> null, Set.of("PAYROLL_APPROVER"), 200));
        routes.add(fresh("PAYROLL-REJECT", "POST", () -> "/api/payroll/batches/" + batch("SUBMITTED") + "/reject",
                () -> "{\"reason\":\"Matrix rejection\"}", Set.of("PAYROLL_APPROVER"), 200));
        routes.add(fresh("PAYROLL-EXECUTE", "POST", () -> "/api/payroll/batches/" + batch("APPROVED") + "/execute?limit=5",
                () -> null, Set.of("PAYROLL_APPROVER", "OPERATIONS"), 200));
        // Operations, outbox and reconciliation
        routes.add(read("OPS-METRICS", "/api/organizations/" + org + "/operations/metrics", Scope.ORGANIZATION,
                Set.of("OPERATIONS")));
        routes.add(read("OPS-AUDIT", "/api/organizations/" + org + "/operations/audit?limit=5", Scope.ORGANIZATION,
                Set.of("OPERATIONS")));
        routes.add(read("OPS-SEARCH", "/api/organizations/" + org + "/operations/search?query=" + org, Scope.ORGANIZATION,
                Set.of("OPERATIONS")));
        routes.add(read("OPS-OUTBOX", "/api/organizations/" + org + "/operations/outbox?limit=5", Scope.ORGANIZATION,
                Set.of("OPERATIONS")));
        routes.add(fresh("OPS-OUTBOX-RETRY", "POST", () -> "/api/organizations/" + org + "/operations/outbox/"
                + deadEvent() + "/retry", () -> null, Set.of("OPERATIONS"), 200));
        routes.add(write("RECON-RUN", "POST", () -> "/api/organizations/" + org + "/operations/reconciliation/runs?limit=10",
                () -> null, Scope.ORGANIZATION, Set.of("OPERATIONS"), 200));
        routes.add(read("RECON-EXCEPTIONS", "/api/organizations/" + org + "/operations/reconciliation/exceptions",
                Scope.ORGANIZATION, Set.of("OPERATIONS")));
        routes.add(fresh("RECON-ASSIGN", "POST", () -> "/api/organizations/" + org
                + "/operations/reconciliation/exceptions/" + messageException() + "/assign",
                () -> "{\"assignedToCustomerId\":\"" + sessions.get("OPERATIONS").customerId + "\"}",
                Set.of("OPERATIONS"), 200));
        routes.add(fresh("RECON-RESOLVE", "POST", () -> "/api/organizations/" + org
                + "/operations/reconciliation/exceptions/" + messageException() + "/resolve",
                () -> "{\"resolution\":\"Rejected callback reviewed\"}", Set.of("OPERATIONS"), 200));
        routes.add(read("ACTUATOR-PROMETHEUS", "/actuator/prometheus", Scope.OPERATIONS_ANY_ORG, Set.of("OPERATIONS")));
        routes.add(read("ACTUATOR-LEDGERCORE", "/actuator/ledgercore", Scope.OPERATIONS_ANY_ORG, Set.of("OPERATIONS")));
        return routes;
    }

    private Route read(String id, String path, Scope scope, Set<String> allowed) {
        return new Route(id, "GET", path, () -> path, () -> null, false, scope, allowed, 200, false, false);
    }

    private Route write(String id, String method, Supplier<String> path, Supplier<String> body, Scope scope,
            Set<String> allowed, int status) {
        return new Route(id, method, path.get().replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "{id}"), path, body, true, scope,
                allowed, status, false, false);
    }

    private Route fresh(String id, String method, Supplier<String> path, Supplier<String> body, Set<String> allowed,
            int status) {
        return new Route(id, method, id, path, body, true, Scope.ORGANIZATION, allowed, status, true, false);
    }

    // ----- fixture -----

    private void fixture() throws Exception {
        if (org != null) {
            return;
        }
        sessions.put("ANONYMOUS", new Session(null));
        Session admin = register("ORG_ADMIN");
        org = UUID.fromString(capture(ID, admin.call("POST", "/api/organizations", "{\"displayName\":\"E8 Matrix Org\"}").body()));
        orgAccount = UUID.fromString(capture(ID, admin.call("POST", "/api/organizations/" + org + "/accounts",
                "{\"displayName\":\"E8 Matrix Operating\"}").body()));
        for (String role : List.of("PAYMENT_MAKER", "PAYMENT_APPROVER", "PAYROLL_MAKER", "PAYROLL_APPROVER", "FINANCE_VIEWER",
                "OPERATIONS")) {
            Session member = register(role);
            assertThat(admin.call("POST", "/api/organizations/" + org + "/members", "{\"email\":\"" + member.email
                    + "\",\"roles\":[\"" + role + "\"]}").statusCode()).isEqualTo(201);
        }
        Session suspended = register("SUSPENDED_MEMBER");
        assertThat(admin.call("POST", "/api/organizations/" + org + "/members", "{\"email\":\"" + suspended.email
                + "\",\"roles\":[\"PAYMENT_APPROVER\",\"OPERATIONS\"]}").statusCode()).isEqualTo(201);
        assertThat(admin.call("POST", "/api/organizations/" + org + "/members/" + suspended.customerId + "/suspend", null)
                .statusCode()).isEqualTo(200);
        Session personal = register("PERSONAL_CUSTOMER");
        personalSource = UUID.fromString(capture(ID, personal.call("POST", "/api/accounts", "{\"displayName\":\"P1\"}").body()));
        personalTarget = UUID.fromString(capture(ID, personal.call("POST", "/api/accounts", "{\"displayName\":\"P2\"}").body()));
        Session outsider = register("CROSS_TENANT_ADMIN");
        assertThat(outsider.call("POST", "/api/organizations", "{\"displayName\":\"E8 Other Org\"}").statusCode())
                .isEqualTo(201);
        Session suspendedOrgAdmin = register("SUSPENDED_ORG_ADMIN");
        UUID suspendedOrg = UUID.fromString(capture(ID, suspendedOrgAdmin.call("POST", "/api/organizations",
                "{\"displayName\":\"E8 Suspended Org\"}").body()));
        // There is no organization-suspension API; the status is set in this disposable test database to verify that a
        // suspended organization's administrator keeps no authority (rolesFor requires an ACTIVE organization).
        jdbc.update("UPDATE organizations SET status = 'SUSPENDED' WHERE id = ?", suspendedOrg);
        assertThat(suspendedOrgAdmin.call("GET", "/api/organizations/" + suspendedOrg + "/accounts", null).statusCode())
                .isEqualTo(404);
    }

    private Session register(String principal) throws Exception {
        Session session = new Session(principal);
        sessions.put(principal, session);
        return session;
    }

    private String newCustomerEmail() {
        try {
            return new Session("NEW_CUSTOMER").email;
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID newMember() {
        try {
            Session member = new Session("NEW_MEMBER");
            assertThat(sessions.get("ORG_ADMIN").call("POST", "/api/organizations/" + org + "/members", "{\"email\":\""
                    + member.email + "\",\"roles\":[\"PAYROLL_MAKER\"]}").statusCode()).isEqualTo(201);
            return member.customerId;
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID newSuspendedMember() {
        UUID member = newMember();
        try {
            assertThat(sessions.get("ORG_ADMIN").call("POST", "/api/organizations/" + org + "/members/" + member
                    + "/suspend", null).statusCode()).isEqualTo(200);
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return member;
    }

    private String paymentBody() {
        return "{\"sourceAccountId\":\"" + orgAccount + "\",\"beneficiaryName\":\"Matrix Supplier\","
                + "\"beneficiaryAccountReference\":\"NZ-E8-1\",\"beneficiaryRoutingCode\":\"SIM-SETTLE\",\"amount\":\"1.00\"}";
    }

    private String transferBody(UUID source, UUID target) {
        return "{\"sourceAccountId\":\"" + source + "\",\"targetAccountId\":\"" + target + "\",\"amount\":\"0.01\"}";
    }

    private String itemsBody() {
        return "{\"items\":[{\"employeeReference\":\"E8-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"employeeName\":\"Matrix Employee\",\"payeeAccountId\":\"" + personalTarget + "\",\"amount\":\"0.50\"}]}";
    }

    private UUID payment(String state) {
        try {
            Session maker = sessions.get("PAYMENT_MAKER");
            UUID payment = UUID.fromString(capture(ID, maker.call("POST", "/api/organizations/" + org + "/payments",
                    paymentBody()).body()));
            if (!"DRAFT".equals(state)) {
                assertThat(maker.call("POST", "/api/payments/" + payment + "/submit", null).statusCode()).isEqualTo(200);
            }
            return payment;
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID batch(String state) {
        try {
            Session maker = sessions.get("PAYROLL_MAKER");
            UUID batch = UUID.fromString(capture(ID, maker.call("POST", "/api/organizations/" + org + "/payroll/batches",
                    "{\"name\":\"Matrix batch\",\"payerAccountId\":\"" + orgAccount + "\"}").body()));
            if ("EMPTY".equals(state)) {
                return batch;
            }
            assertThat(maker.call("POST", "/api/payroll/batches/" + batch + "/items", itemsBody()).statusCode())
                    .isEqualTo(200);
            if ("DRAFT".equals(state)) {
                return batch;
            }
            assertThat(maker.call("POST", "/api/payroll/batches/" + batch + "/submit", null).statusCode()).isEqualTo(200);
            if ("APPROVED".equals(state)) {
                assertThat(sessions.get("PAYROLL_APPROVER").call("POST", "/api/payroll/batches/" + batch + "/approve", null)
                        .statusCode()).isEqualTo(200);
            }
            return batch;
        }
        catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID deadEvent() {
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, event_key, event_type, aggregate_type, aggregate_id, organization_id, payload,
                    status, attempt_count)
                VALUES (?, ?, 'matrix.fact', 'MATRIX', ?, ?, '{}'::jsonb, 'PENDING', 0)
                """, event, "matrix.fact:" + event, event, org);
        jdbc.update("UPDATE outbox_events SET status = 'PROCESSING', attempt_count = 5, lease_until = clock_timestamp() WHERE id = ?", event);
        jdbc.update("UPDATE outbox_events SET status = 'DEAD', lease_until = NULL, last_error = 'matrix' WHERE id = ?", event);
        return event;
    }

    private UUID messageException() {
        UUID exception = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation_exceptions (id, category, severity, operation_id, organization_id, expected_facts,
                    observed_facts)
                VALUES (?, 'CLEARING_CALLBACK_OUT_OF_ORDER', 'HIGH', ?, ?, '{"resourceType":"CLEARING_CALLBACK"}'::jsonb,
                    '{"observedSequence":9}'::jsonb)
                """, exception, UUID.randomUUID(), org);
        return exception;
    }

    private String fingerprint() {
        return jdbc.queryForObject("""
                SELECT md5(concat_ws('|',
                    (SELECT count(*) || ':' || COALESCE(sum(amount_minor), 0) FROM postings),
                    (SELECT count(*) FROM journal_entries),
                    (SELECT string_agg(id || status || COALESCE(approved_by_customer_id::text, '') || version, ',' ORDER BY id)
                       FROM corporate_payment_intents WHERE organization_id = ?),
                    (SELECT string_agg(id || status, ',' ORDER BY id) FROM payroll_batches WHERE organization_id = ?),
                    (SELECT count(*) FROM payroll_items i JOIN payroll_batches b ON b.id = i.batch_id WHERE b.organization_id = ?),
                    (SELECT string_agg(customer_id || status, ',' ORDER BY customer_id) FROM organization_memberships
                       WHERE organization_id = ?),
                    (SELECT string_agg(customer_id || role, ',' ORDER BY customer_id, role) FROM organization_membership_roles
                       WHERE organization_id = ?),
                    (SELECT count(*) FROM accounts WHERE owner_organization_id = ?),
                    (SELECT string_agg(id || status, ',' ORDER BY id) FROM reconciliation_exceptions WHERE organization_id = ?),
                    (SELECT string_agg(id || status || attempt_count, ',' ORDER BY id) FROM outbox_events
                       WHERE organization_id = ? AND status = 'DEAD')))
                """, String.class, org, org, org, org, org, org, org, org);
    }

    private HttpResponse<String> call(String principal, String method, String path, String body) throws Exception {
        return sessions.get(principal).call(method, path, body);
    }

    private static String capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertThat(matcher.find()).as(text).isTrue();
        return matcher.group(1);
    }

    enum Scope {
        ANY_AUTHENTICATED, OWNER, ORGANIZATION, OPERATIONS_ANY_ORG
    }

    record Route(String id, String method, String template, Supplier<String> target, Supplier<String> body,
            boolean mutating, Scope scope, Set<String> allowed, int allowedStatus, boolean freshTargetPerAllowed,
            boolean makerConflict) {
    }

    private final class Session {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        final String email;
        final UUID customerId;

        Session(String principal) throws Exception {
            if (principal == null) {
                email = null;
                customerId = null;
                return;
            }
            email = "e8-" + principal.toLowerCase().replace("_", "").substring(0, Math.min(16, principal.length() - 1)) + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.test";
            HttpResponse<String> response = call("POST", "/api/auth/register", "{\"email\":\"" + email
                    + "\",\"password\":\"E8-Matrix-Password!\",\"displayName\":\"E8 " + principal + "\"}");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            customerId = UUID.fromString(capture(ID, response.body()));
        }

        HttpResponse<String> call(String method, String path, String body) throws Exception {
            HttpResponse<String> csrf = send("GET", "/api/auth/csrf", null, null);
            return send(method, path, body, capture(TOKEN, csrf.body()));
        }

        HttpResponse<String> send(String method, String path, String body, String csrfToken) throws Exception {
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
            if (path.contains("/api/transfers")) {
                request.header("Idempotency-Key", "e8-" + UUID.randomUUID());
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
