package nz.ac.ledgercore.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.identity.IdentityService;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationException;
import nz.ac.ledgercore.organizations.OrganizationRole;
import nz.ac.ledgercore.organizations.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class PayrollPostgresIntegrationTests {

    private static final String PASSWORD = "Payroll-Scenario-Password!";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IdentityService identityService;

    @Autowired
    AccountService accountService;

    @Autowired
    OrganizationService organizationService;

    @Autowired
    PayrollService payrollService;

    @Autowired
    PayrollExecutionService executionService;

    @Autowired
    PayrollItemExecutor itemExecutor;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void processesAndReconcilesFiveHundredSyntheticEmployeesExactlyOnce() throws Exception {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "Friday payroll payer");
        accountService.simulateDeposit(payer, new Money(1_000_000L));

        List<UUID> employeeAccounts = createSyntheticEmployees(500);
        List<PayrollService.NewPayrollItem> items = new ArrayList<>(500);
        long expectedTotal = 0;
        for (int index = 0; index < employeeAccounts.size(); index++) {
            long amountMinor = (10L + index % 5) * 100L;
            expectedTotal = Math.addExact(expectedTotal, amountMinor);
            String employeeNumber = "%04d".formatted(index + 1);
            items.add(new PayrollService.NewPayrollItem("EMP-" + employeeNumber,
                    "Synthetic Employee " + employeeNumber, employeeAccounts.get(index),
                    new Money(amountMinor).toNzdString()));
        }

        PayrollViews.BatchView draft = createDraft(actors, payer, items);
        assertThat(draft.itemCount()).isEqualTo(500);
        assertThat(draft.totalAmount()).isEqualTo("6000.00");
        assertThat(expectedTotal).isEqualTo(600_000L);

        PayrollViews.BatchView submitted = payrollService.submit(actors.makerId(), draft.id());
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(submitted.contentDigest()).matches("[0-9a-f]{64}");
        assertThat(payrollService.submit(actors.makerId(), draft.id()).contentDigest())
                .isEqualTo(submitted.contentDigest());
        assertThatThrownBy(() -> payrollService.approve(actors.makerId(), draft.id()))
                .isInstanceOf(OrganizationAuthorizationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE payroll_items SET amount_minor = amount_minor + 1
                WHERE batch_id = ? AND sequence_no = 1
                """, draft.id())).isInstanceOf(DataAccessException.class);

        PayrollViews.BatchView approved = payrollService.approve(actors.approverId(), draft.id());
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvedContentDigest()).isEqualTo(submitted.contentDigest());
        assertThatThrownBy(() -> payrollService.addItems(actors.makerId(), draft.id(), List.of(
                new PayrollService.NewPayrollItem("EXTRA", "Late change", employeeAccounts.getFirst(), "1.00"))))
                .isInstanceOf(PayrollException.class)
                .hasMessageContaining("only be added");

        UUID firstItemId = payrollService.items(actors.makerId(), draft.id()).getFirst().id();
        payrollService.startProcessing(draft.id(), actors.approverId());
        var duplicateExecutions = executeSameItemConcurrently(draft.id(), firstItemId, actors.approverId());
        assertThat(List.of(duplicateExecutions.first().attempted(), duplicateExecutions.second().attempted()))
                .containsExactlyInAnyOrder(true, false);
        assertThat(duplicateExecutions.first().transferId()).isEqualTo(duplicateExecutions.second().transferId());

        PayrollViews.BatchView partial = executionService.execute(actors.approverId(), draft.id(), 136);
        assertThat(partial.status()).isEqualTo("PROCESSING");
        assertThat(partial.succeededCount()).isEqualTo(137);
        assertThat(partial.pendingCount()).isEqualTo(363);
        PayrollViews.BatchView resumed = executionService.execute(actors.approverId(), draft.id(), 1_000);
        assertThat(resumed.status()).isEqualTo("COMPLETED");
        assertThat(resumed.succeededCount()).isEqualTo(500);
        assertThat(resumed.failedCount()).isZero();
        assertThat(resumed.pendingCount()).isZero();
        assertThat(resumed.totalAmount()).isEqualTo("6000.00");
        assertThat(resumed.succeededAmount()).isEqualTo("6000.00");
        assertThat(executionService.execute(actors.approverId(), draft.id(), 1_000).succeededCount()).isEqualTo(500);

        assertThat(count("SELECT count(*) FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id "
                + "WHERE i.batch_id = ? AND r.status = 'SUCCEEDED'", draft.id())).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM transfers WHERE source_organization_id = ? "
                + "AND owner_customer_id = ?", actors.organizationId(), actors.makerId())).isEqualTo(500);
        assertThat(count("SELECT count(*) FROM idempotency_records WHERE owner_customer_id = ? "
                + "AND operation = 'TRANSFER' AND idempotency_key LIKE ?", actors.makerId(),
                "payroll-" + draft.id() + "-%")).isEqualTo(500);
        assertThat(count("""
                SELECT count(*) FROM outbox_events
                WHERE event_type = 'payroll.item.posted'
                  AND payload ->> 'batchId' = ?
                  AND organization_id = ? AND actor_customer_id = ?
                """, draft.id().toString(), actors.organizationId(), actors.approverId())).isEqualTo(500);
        assertThat(count("""
                SELECT count(*) FROM (
                    SELECT t.id
                    FROM transfers t JOIN postings p ON p.journal_entry_id = t.journal_entry_id
                    WHERE t.source_account_id = ?
                    GROUP BY t.id
                    HAVING count(p.id) <> 2
                        OR sum(p.amount_minor) FILTER (WHERE p.side = 'DEBIT')
                           <> sum(p.amount_minor) FILTER (WHERE p.side = 'CREDIT')
                ) unbalanced
                """, payer)).isZero();
        assertThat(count("""
                SELECT count(*) FROM accounts a
                JOIN ledger_accounts la ON la.customer_account_id = a.id AND la.kind = 'CUSTOMER_DEPOSIT'
                JOIN account_balance_projection p ON p.ledger_account_id = la.id
                WHERE (a.id = ? OR EXISTS (
                    SELECT 1 FROM payroll_items i WHERE i.batch_id = ? AND i.payee_account_id = a.id
                ))
                  AND p.balance_minor <> (
                    SELECT COALESCE(sum(CASE WHEN posting.side = 'CREDIT' THEN posting.amount_minor
                                             ELSE -posting.amount_minor END), 0)
                    FROM postings posting WHERE posting.ledger_account_id = la.id
                  )
                """, payer, draft.id())).isZero();
        assertThat(balance(payer)).isEqualTo(500_000L);
        assertThat(balance(employeeAccounts.getFirst())).isEqualTo(101_000L);
    }

    @Test
    void insufficientFundsAreDurableWithoutPostingOrOverdraft() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "Insufficient payroll payer");
        UUID payee = createPersonalAccount("Underfunded payroll employee");
        PayrollViews.BatchView draft = createDraft(actors, payer, List.of(
                new PayrollService.NewPayrollItem("EMP-UNDERFUNDED", "Underfunded Employee", payee, "2000.00")));
        payrollService.submit(actors.makerId(), draft.id());
        payrollService.approve(actors.approverId(), draft.id());

        PayrollViews.BatchView result = executionService.execute(actors.approverId(), draft.id(), 10);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.succeededCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(payrollService.items(actors.makerId(), draft.id()).getFirst().errorCode())
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isZero();
        assertThat(balance(payer)).isEqualTo(100_000L);
    }

    @Test
    void transferAndPayrollResultRollbackTogetherOnDatabaseWriteFailure() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "Rollback payroll payer");
        UUID payee = createPersonalAccount("Rollback payroll employee");
        PayrollViews.BatchView draft = createDraft(actors, payer, List.of(
                new PayrollService.NewPayrollItem("EMP-ROLLBACK", "Rollback Employee", payee, "10.00")));
        payrollService.submit(actors.makerId(), draft.id());
        payrollService.approve(actors.approverId(), draft.id());
        payrollService.startProcessing(draft.id(), actors.approverId());
        long sourceBalanceBefore = balance(payer);

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_fail_payroll_result ON payroll_item_results");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_fail_payroll_result()");
        jdbcTemplate.execute("""
                CREATE FUNCTION test_fail_payroll_result() RETURNS TRIGGER AS $$
                BEGIN
                    IF NEW.status = 'SUCCEEDED' THEN
                        RAISE EXCEPTION 'injected result-write failure' USING ERRCODE = 'P0001';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_test_fail_payroll_result
                BEFORE UPDATE ON payroll_item_results
                FOR EACH ROW EXECUTE FUNCTION test_fail_payroll_result()
                """);
        try {
            assertThatThrownBy(() -> executionService.execute(actors.approverId(), draft.id(), 1))
                    .isInstanceOf(DataAccessException.class);
        }
        finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_test_fail_payroll_result ON payroll_item_results");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_fail_payroll_result()");
        }

        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isZero();
        assertThat(count("SELECT count(*) FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id "
                + "WHERE i.batch_id = ? AND r.status = 'PENDING'", draft.id())).isEqualTo(1);
        assertThat(balance(payer)).isEqualTo(sourceBalanceBefore);

        PayrollViews.BatchView retried = executionService.execute(actors.approverId(), draft.id(), 1);
        assertThat(retried.status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isEqualTo(1);
    }

    @Test
    void concurrentContentMutationWaitsForApprovalAndThenIsRejected() throws Exception {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "Race payroll payer");
        UUID payee = createPersonalAccount("Race payroll employee");
        PayrollViews.BatchView draft = createDraft(actors, payer, List.of(
                new PayrollService.NewPayrollItem("EMP-RACE", "Race Employee", payee, "10.00")));
        PayrollViews.BatchView submitted = payrollService.submit(actors.makerId(), draft.id());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch approvalHasLock = new CountDownLatch(1);
        CountDownLatch allowApproval = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PayrollViews.BatchView> approval = executor.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM payroll_batches WHERE id = ? FOR UPDATE", UUID.class,
                        draft.id());
                approvalHasLock.countDown();
                await(allowApproval);
                return payrollService.approve(actors.approverId(), draft.id());
            }));
            assertThat(approvalHasLock.await(5, TimeUnit.SECONDS)).isTrue();

            Future<PayrollViews.BatchView> mutation = executor.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.execute("SET LOCAL application_name = 'payroll-content-race'");
                return payrollService.addItems(actors.makerId(), draft.id(), List.of(
                        new PayrollService.NewPayrollItem("EMP-LATE", "Late Employee", payee, "1.00")));
            }));

            boolean blockedOnApprovalLock = awaitPostgresLock("payroll-content-race", 5_000);
            allowApproval.countDown();
            PayrollViews.BatchView approved = approval.get(10, TimeUnit.SECONDS);
            assertThat(approved.status()).isEqualTo("APPROVED");
            assertThat(blockedOnApprovalLock).isTrue();
            try {
                mutation.get(10, TimeUnit.SECONDS);
                throw new AssertionError("A concurrent payroll content mutation unexpectedly succeeded.");
            }
            catch (ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(PayrollException.class)
                        .hasMessageContaining("only be added");
            }
            assertThat(payrollService.items(actors.makerId(), draft.id())).hasSize(1);
            assertThat(payrollService.get(actors.approverId(), draft.id()).approvedContentDigest())
                    .isEqualTo(submitted.contentDigest());
        }
        finally {
            allowApproval.countDown();
            executor.shutdownNow();
        }
    }

    private PayrollViews.BatchView createDraft(Actors actors, UUID payer,
            List<PayrollService.NewPayrollItem> items) {
        PayrollViews.BatchView draft = payrollService.createDraft(actors.makerId(), actors.organizationId(),
                "Friday Payroll " + UUID.randomUUID(), payer);
        return payrollService.addItems(actors.makerId(), draft.id(), items);
    }

    private Actors createActors() {
        var maker = identityService.register("maker-" + UUID.randomUUID() + "@example.test", PASSWORD,
                "Payroll Maker");
        var approver = identityService.register("approver-" + UUID.randomUUID() + "@example.test", PASSWORD,
                "Payroll Approver");
        var admin = identityService.register("admin-" + UUID.randomUUID() + "@example.test", PASSWORD,
                "Payroll Organization Admin");
        var organization = organizationService.create(admin.id(), "Synthetic Payroll Ltd "
                + UUID.randomUUID().toString().substring(0, 8));
        organizationService.addMember(admin.id(), organization.id(), maker.email(), List.of(OrganizationRole.PAYROLL_MAKER));
        organizationService.addMember(admin.id(), organization.id(), approver.email(),
                List.of(OrganizationRole.PAYROLL_APPROVER, OrganizationRole.OPERATIONS));
        return new Actors(organization.id(), maker.id(), maker.email(), approver.id(), approver.email());
    }

    private UUID createPersonalAccount(String name) {
        UUID customerId = identityService.register("employee-" + UUID.randomUUID() + "@example.test", PASSWORD,
                name).id();
        return accountService.openAccount(customerId, name + " account");
    }

    private List<UUID> createSyntheticEmployees(int numberOfEmployees) {
        String passwordHash = passwordEncoder.encode(PASSWORD);
        List<UUID> customers = new ArrayList<>(numberOfEmployees);
        List<Object[]> insertRows = new ArrayList<>(numberOfEmployees);
        for (int index = 0; index < numberOfEmployees; index++) {
            UUID customerId = UUID.randomUUID();
            customers.add(customerId);
            String employeeNumber = "%04d".formatted(index + 1);
            insertRows.add(new Object[] { customerId, "synthetic-" + UUID.randomUUID() + "@example.test",
                    passwordHash, "Synthetic Employee " + employeeNumber });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO customers (id, email_normalized, password_hash, display_name) VALUES (?, ?, ?, ?)
                """, insertRows);

        List<UUID> accounts = new ArrayList<>(numberOfEmployees);
        for (int index = 0; index < customers.size(); index++) {
            String employeeNumber = "%04d".formatted(index + 1);
            accounts.add(accountService.openAccount(customers.get(index), "Synthetic Employee " + employeeNumber));
        }
        return accounts;
    }

    private DuplicateExecution executeSameItemConcurrently(UUID batchId, UUID itemId, UUID releaserActorId)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PayrollItemExecutor.ExecutionResult> first = executor.submit(() -> {
                start.await();
                return itemExecutor.executeOne(batchId, itemId, releaserActorId);
            });
            Future<PayrollItemExecutor.ExecutionResult> second = executor.submit(() -> {
                start.await();
                return itemExecutor.executeOne(batchId, itemId, releaserActorId);
            });
            start.countDown();
            return new DuplicateExecution(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        finally {
            executor.shutdownNow();
        }
    }

    private boolean awaitPostgresLock(String applicationName, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE application_name = ? AND wait_event_type = 'Lock'
                    )
                    """, Boolean.class, applicationName);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the concurrent approval test gate.");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent approval test was interrupted.", exception);
        }
    }

    private int count(String sql, Object... parameters) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, parameters);
        return result == null ? 0 : result;
    }

    private long balance(UUID accountId) {
        Long result = jdbcTemplate.queryForObject("""
                SELECT p.balance_minor
                FROM ledger_accounts la JOIN account_balance_projection p ON p.ledger_account_id = la.id
                WHERE la.customer_account_id = ? AND la.kind = 'CUSTOMER_DEPOSIT'
                """, Long.class, accountId);
        return result == null ? 0L : result;
    }

    private record Actors(UUID organizationId, UUID makerId, String makerEmail, UUID approverId, String approverEmail) {
    }

    private record DuplicateExecution(PayrollItemExecutor.ExecutionResult first,
            PayrollItemExecutor.ExecutionResult second) {
    }
}
