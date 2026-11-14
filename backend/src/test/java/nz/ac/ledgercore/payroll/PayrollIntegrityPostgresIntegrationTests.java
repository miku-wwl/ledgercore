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
import nz.ac.ledgercore.api.ApiNotFoundException;
import nz.ac.ledgercore.identity.IdentityService;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.organizations.OrganizationRole;
import nz.ac.ledgercore.organizations.OrganizationService;
import nz.ac.ledgercore.transfers.TransferErrorCode;
import nz.ac.ledgercore.transfers.TransferException;
import nz.ac.ledgercore.transfers.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Round 2 independent-audit regression suite for payroll intent/result integrity (R2-01, R2-02), creator authority
 * changes (R2-05), reserved payroll operation identities (R2-06), authorization ordering (R2-07), and concurrency
 * gaps found in the Round 1 suite. Every test runs against real PostgreSQL through the production services; direct
 * SQL is used only to model a privileged database client or a defect that bypasses the application layer.
 */
@ActiveProfiles("test")
@SpringBootTest
class PayrollIntegrityPostgresIntegrationTests {

    private static final String PASSWORD = "Round2-Payroll-Integrity!";

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
    TransferService transferService;

    @Autowired
    PlatformTransactionManager transactionManager;

    // ----- R2-01: payroll intent can never leave, or be rewritten in, a non-draft batch -----

    @Test
    void approvedItemsCannotBeMovedToAnotherBatchOrRewrittenThere() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-01 payer");
        UUID payee = createPersonalAccount("R2-01 employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-1", payee, "15.00"),
                item("EMP-2", payee, "12.00"));
        PayrollViews.BatchView draft = createDraft(actors, payer, item("EMP-D1", payee, "0.10"));
        List<UUID> approvedItems = itemIds(approved.id());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE payroll_items SET batch_id = ?, sequence_no = 2 WHERE id = ?
                """, draft.id(), approvedItems.get(0))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE payroll_items SET batch_id = ?, sequence_no = 3, amount_minor = 1, employee_reference = 'EMP-D3'
                WHERE id = ?
                """, draft.id(), approvedItems.get(1))).isInstanceOf(DataAccessException.class);

        assertThat(itemIds(approved.id())).containsExactlyElementsOf(approvedItems);
        assertThat(itemIds(draft.id())).hasSize(1);
        PayrollViews.BatchView executed = executionService.execute(actors.approverId(), approved.id(), 10);
        assertThat(executed.status()).isEqualTo("COMPLETED");
        assertThat(executed.succeededAmount()).isEqualTo("27.00");
    }

    @Test
    void completedPayrollHistoryCannotBeMovedOrErased() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-01 history payer");
        UUID payee = createPersonalAccount("R2-01 history employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-H1", payee, "5.00"));
        assertThat(executionService.execute(actors.approverId(), approved.id(), 10).status()).isEqualTo("COMPLETED");
        PayrollViews.BatchView draft = createDraft(actors, payer, item("EMP-H2", payee, "1.00"));
        UUID paidItem = itemIds(approved.id()).getFirst();
        UUID transferId = jdbcTemplate.queryForObject("SELECT transfer_id FROM payroll_item_results WHERE item_id = ?",
                UUID.class, paidItem);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE payroll_items SET batch_id = ?, sequence_no = 7 WHERE id = ?",
                draft.id(), paidItem)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM payroll_items WHERE id = ?", paidItem))
                .isInstanceOf(DataAccessException.class);

        assertThat(itemIds(approved.id())).containsExactly(paidItem);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status || ':' || transfer_id FROM payroll_item_results WHERE item_id = ?
                """, String.class, paidItem)).isEqualTo("SUCCEEDED:" + transferId);
    }

    @Test
    void itemChangesRacingApprovalCannotAlterTheApprovedContent() throws Exception {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-01 race payer");
        UUID payee = createPersonalAccount("R2-01 race employee");
        PayrollViews.BatchView submitted = createDraft(actors, payer, item("EMP-R1", payee, "3.00"),
                item("EMP-R2", payee, "4.00"));
        submitted = payrollService.submit(actors.makerId(), submitted.id());
        PayrollViews.BatchView draft = createDraft(actors, payer, item("EMP-RD", payee, "1.00"));
        UUID sourceBatch = submitted.id();
        List<UUID> sourceItems = itemIds(sourceBatch);
        String submittedDigest = submitted.contentDigest();

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch approvalHoldsBatchLock = new CountDownLatch(1);
        CountDownLatch releaseApproval = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<PayrollViews.BatchView> approval = executor.submit(() -> transactions.execute(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM payroll_batches WHERE id = ? FOR UPDATE", UUID.class,
                        sourceBatch);
                approvalHoldsBatchLock.countDown();
                await(releaseApproval);
                return payrollService.approve(actors.approverId(), sourceBatch);
            }));
            assertThat(approvalHoldsBatchLock.await(5, TimeUnit.SECONDS)).isTrue();
            // An in-place intent change must queue behind the approval's batch lock, then see the decision.
            Future<Integer> inPlaceChange = executor.submit(() -> transactions.execute(status -> {
                jdbcTemplate.execute("SET LOCAL application_name = 'r2-01-in-place-race'");
                return jdbcTemplate.update("UPDATE payroll_items SET amount_minor = amount_minor + 1 WHERE id = ?",
                        sourceItems.get(1));
            }));
            // Moving an item out must never succeed, whether or not it waits for the source batch.
            Future<Integer> move = executor.submit(() -> transactions.execute(status ->
                    jdbcTemplate.update("UPDATE payroll_items SET batch_id = ?, sequence_no = 5 WHERE id = ?",
                            draft.id(), sourceItems.get(0))));
            assertThatThrownBy(() -> move.get(10, TimeUnit.SECONDS))
                    .as("an item left a batch while its approval was in progress")
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(DataAccessException.class);

            boolean inPlaceChangeQueued = awaitPostgresLock("r2-01-in-place-race", 5_000);
            releaseApproval.countDown();
            assertThat(approval.get(10, TimeUnit.SECONDS).status()).isEqualTo("APPROVED");
            assertThat(inPlaceChangeQueued).as("in-place changes serialize on the item's batch row").isTrue();
            assertThatThrownBy(() -> inPlaceChange.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(DataAccessException.class);
        }
        finally {
            releaseApproval.countDown();
            executor.shutdownNow();
        }
        assertThat(itemIds(sourceBatch)).containsExactlyElementsOf(sourceItems);
        assertThat(payrollService.get(actors.approverId(), sourceBatch).approvedContentDigest()).isEqualTo(submittedDigest);
        assertThat(payrollService.startProcessing(sourceBatch, actors.approverId()).status()).isEqualTo("PROCESSING");
    }

    @Test
    void draftItemsStayInTheirBatchButCanBeDeletedWithTheirPendingResult() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-01 draft payer");
        UUID payee = createPersonalAccount("R2-01 draft employee");
        PayrollViews.BatchView first = createDraft(actors, payer, item("EMP-A", payee, "1.00"), item("EMP-B", payee, "2.00"));
        PayrollViews.BatchView second = createDraft(actors, payer, item("EMP-C", payee, "3.00"));
        List<UUID> firstItems = itemIds(first.id());

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE payroll_items SET batch_id = ?, sequence_no = 9 WHERE id = ?",
                second.id(), firstItems.get(0))).isInstanceOf(DataAccessException.class);
        assertThat(jdbcTemplate.update("UPDATE payroll_items SET amount_minor = 150 WHERE id = ?", firstItems.get(0)))
                .isEqualTo(1);
        assertThat(jdbcTemplate.update("DELETE FROM payroll_items WHERE id = ?", firstItems.get(0))).isEqualTo(1);

        assertThat(count("SELECT count(*) FROM payroll_item_results WHERE item_id = ?", firstItems.get(0))).isZero();
        assertThat(count("SELECT count(*) FROM payroll_item_results WHERE item_id = ?", firstItems.get(1))).isEqualTo(1);
        assertThat(payrollService.get(actors.makerId(), first.id()).itemCount()).isEqualTo(1);
    }

    // ----- R2-02: result rows are durable, born pending, and complete before a batch can finish -----

    @Test
    void payrollResultsCannotBeDeletedInAnyState() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-02 payer");
        UUID payee = createPersonalAccount("R2-02 employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-S", payee, "10.00"),
                item("EMP-F", payee, "2000.00"), item("EMP-P", payee, "1.00"));
        PayrollViews.BatchView processing = executionService.execute(actors.approverId(), approved.id(), 2);
        assertThat(processing.status()).isEqualTo("PROCESSING");
        List<UUID> items = itemIds(approved.id());

        for (UUID itemId : items) {
            assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM payroll_item_results WHERE item_id = ?", itemId))
                    .as("result for item %s", itemId).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM payroll_item_results WHERE batch_id = ?", approved.id()))
                .isInstanceOf(DataAccessException.class);

        PayrollViews.BatchView view = payrollService.get(actors.approverId(), approved.id());
        assertThat(List.of(view.itemCount(), view.succeededCount(), view.failedCount(), view.pendingCount()))
                .containsExactly(3, 1, 1, 1);
    }

    @Test
    void batchCompletionFailsClosedWhenAResultRowIsMissing() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-02 completion payer");
        UUID payee = createPersonalAccount("R2-02 completion employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-1", payee, "4.00"),
                item("EMP-2", payee, "6.00"));
        assertThat(executionService.execute(actors.approverId(), approved.id(), 1).status()).isEqualTo("PROCESSING");
        UUID unpaidItem = itemIds(approved.id()).get(1);

        // Model a defect or privileged client that bypasses every user-defined trigger on the result table.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("ALTER TABLE payroll_item_results DISABLE TRIGGER USER");
            jdbcTemplate.update("DELETE FROM payroll_item_results WHERE item_id = ?", unpaidItem);
            jdbcTemplate.execute("ALTER TABLE payroll_item_results ENABLE TRIGGER USER");
        });

        assertThatThrownBy(() -> executionService.execute(actors.approverId(), approved.id(), 10))
                .isInstanceOf(PayrollException.class)
                .satisfies(exception -> assertThat(((PayrollException) exception).code())
                        .isEqualTo("PAYROLL_RESULTS_INCOMPLETE"));
        assertThat(batchStatus(approved.id())).isEqualTo("PROCESSING");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE payroll_batches SET status = 'COMPLETED', completed_at = clock_timestamp()
                WHERE id = ? AND status = 'PROCESSING'
                """, approved.id())).isInstanceOf(DataAccessException.class);
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isEqualTo(1);
    }

    @Test
    void resultsCannotBeForgedOrAdvancedOutsideProcessing() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-02 forgery payer");
        UUID payee = createPersonalAccount("R2-02 forgery employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-X", payee, "7.00"));
        UUID approvedItem = itemIds(approved.id()).getFirst();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE payroll_item_results
                SET status = 'FAILED', attempt_count = 1, error_code = 'FORGED', error_message = 'skip before execution',
                    completed_at = clock_timestamp()
                WHERE item_id = ?
                """, approvedItem)).isInstanceOf(DataAccessException.class);

        PayrollViews.BatchView draft = createDraft(actors, payer, item("EMP-Y", payee, "1.00"));
        UUID forgedItem = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO payroll_items (id, batch_id, sequence_no, employee_reference, employee_name,
                        payee_account_id, payee_owner_customer_id, amount_minor)
                    SELECT ?, ?, 2, 'EMP-FORGED', 'Forged Employee', id, owner_customer_id, 100 FROM accounts WHERE id = ?
                    """, forgedItem, draft.id(), payee);
            jdbcTemplate.update("""
                    INSERT INTO payroll_item_results (item_id, status, attempt_count, error_code, error_message, completed_at)
                    VALUES (?, 'FAILED', 1, 'FORGED', 'born terminal', clock_timestamp())
                    """, forgedItem);
        })).isInstanceOf(DataAccessException.class);

        assertThat(count("SELECT count(*) FROM payroll_items WHERE id = ?", forgedItem)).isZero();
        assertThat(executionService.execute(actors.approverId(), approved.id(), 10).status()).isEqualTo("COMPLETED");
    }

    @Test
    void succeededResultMustReferenceTheTransferThatPaysItsItem() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-02 linkage payer");
        UUID payee = createPersonalAccount("R2-02 linkage employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-L", payee, "9.00"));
        payrollService.startProcessing(approved.id(), actors.approverId());
        UUID itemId = itemIds(approved.id()).getFirst();
        var unrelatedOwner = identityService.register("r2-unrelated-" + UUID.randomUUID() + "@example.test", PASSWORD,
                "Unrelated Customer");
        UUID unrelatedSource = accountService.openAccount(unrelatedOwner.id(), "Unrelated source");
        UUID unrelatedTarget = accountService.openAccount(unrelatedOwner.id(), "Unrelated target");
        UUID unrelatedTransfer = transferService.transfer(unrelatedOwner.id(), unrelatedSource, unrelatedTarget,
                new Money(900L), "r2-unrelated-" + UUID.randomUUID()).transferId();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE payroll_item_results
                SET status = 'SUCCEEDED', attempt_count = 1, transfer_id = ?, completed_at = clock_timestamp()
                WHERE item_id = ?
                """, unrelatedTransfer, itemId)).isInstanceOf(DataAccessException.class);

        PayrollViews.BatchView completed = executionService.execute(actors.approverId(), approved.id(), 10);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        UUID linked = jdbcTemplate.queryForObject("SELECT transfer_id FROM payroll_item_results WHERE item_id = ?",
                UUID.class, itemId);
        assertThat(linked).isNotEqualTo(unrelatedTransfer);
        assertThat(count("""
                SELECT count(*) FROM transfers WHERE id = ? AND source_account_id = ? AND target_account_id = ?
                  AND amount_minor = 900
                """, linked, payer, payee)).isEqualTo(1);
    }

    @Test
    void progressCountsStayExactAndIsolatedWhenOtherOrganizationsHavePayrollRows() {
        Actors target = createActors();
        UUID payer = accountService.openOrganizationAccount(target.organizationId(), "R2 progress payer");
        UUID payee = createPersonalAccount("R2 progress employee");
        PayrollViews.BatchView targetBatch = approvedBatch(target, payer, item("T-1", payee, "1.00"),
                item("T-2", payee, "1.00"), item("T-3", payee, "2000.00"), item("T-4", payee, "1.00"));
        executionService.execute(target.approverId(), targetBatch.id(), 3);

        Actors other = createActors();
        UUID otherPayer = accountService.openOrganizationAccount(other.organizationId(), "Other org payer");
        List<PayrollService.NewPayrollItem> otherItems = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            otherItems.add(item("O-" + index, payee, "1.00"));
        }
        PayrollViews.BatchView otherBatch = approvedBatch(other, otherPayer,
                otherItems.toArray(PayrollService.NewPayrollItem[]::new));
        executionService.execute(other.approverId(), otherBatch.id(), 25);

        PayrollViews.BatchView view = payrollService.get(target.makerId(), targetBatch.id());
        assertThat(List.of(view.itemCount(), view.succeededCount(), view.failedCount(), view.pendingCount()))
                .containsExactly(4, 2, 1, 1);
        assertThat(view.succeededAmount()).isEqualTo("2.00");
        PayrollViews.BatchView otherView = payrollService.get(other.makerId(), otherBatch.id());
        assertThat(List.of(otherView.itemCount(), otherView.succeededCount(), otherView.pendingCount()))
                .containsExactly(40, 25, 15);
        assertThatThrownBy(() -> payrollService.get(other.makerId(), targetBatch.id()))
                .isInstanceOf(ApiNotFoundException.class);
        assertThatThrownBy(() -> payrollService.items(target.approverId(), otherBatch.id()))
                .isInstanceOf(ApiNotFoundException.class);
        assertThat(executionService.execute(target.approverId(), targetBatch.id(), 10).status())
                .isEqualTo("PARTIALLY_COMPLETED");
    }

    // ----- R2-05: the batch creator's debit authority is evaluated when each item executes -----

    @Test
    void revokedCreatorRoleFailsItemsClosedWithAnExplicitReason() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-05 role payer");
        UUID payee = createPersonalAccount("R2-05 role employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-1", payee, "1.00"),
                item("EMP-2", payee, "2.00"));
        jdbcTemplate.update("""
                DELETE FROM organization_membership_roles
                WHERE organization_id = ? AND customer_id = ? AND role = 'PAYROLL_MAKER'
                """, actors.organizationId(), actors.makerId());

        PayrollViews.BatchView result = executionService.execute(actors.approverId(), approved.id(), 10);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(payrollService.items(actors.approverId(), approved.id()))
                .extracting(PayrollViews.ItemView::errorCode).containsOnly("DEBIT_AUTHORITY_REVOKED");
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isZero();
        assertThat(balance(payer)).isEqualTo(100_000L);
    }

    @Test
    void suspendedCreatorMembershipFailsItemsClosedWithAnExplicitReason() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-05 membership payer");
        UUID payee = createPersonalAccount("R2-05 membership employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-1", payee, "1.00"));
        jdbcTemplate.update("""
                UPDATE organization_memberships SET status = 'SUSPENDED' WHERE organization_id = ? AND customer_id = ?
                """, actors.organizationId(), actors.makerId());

        PayrollViews.BatchView result = executionService.execute(actors.approverId(), approved.id(), 10);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(payrollService.items(actors.approverId(), approved.id()).getFirst().errorCode())
                .isEqualTo("DEBIT_AUTHORITY_REVOKED");
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isZero();
    }

    @Test
    void suspendedOrganizationCannotStartPayrollExecution() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-05 suspended org payer");
        UUID payee = createPersonalAccount("R2-05 suspended org employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-1", payee, "1.00"));
        jdbcTemplate.update("UPDATE organizations SET status = 'SUSPENDED' WHERE id = ?", actors.organizationId());

        assertThatThrownBy(() -> executionService.execute(actors.approverId(), approved.id(), 10))
                .isInstanceOf(ApiNotFoundException.class);
        assertThat(batchStatus(approved.id())).isEqualTo("APPROVED");
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isZero();
    }

    @Test
    void approvalRemainsValidAfterTheApproverLosesTheirRole() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-05 approver change payer");
        UUID payee = createPersonalAccount("R2-05 approver change employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-1", payee, "3.00"));
        jdbcTemplate.update("""
                DELETE FROM organization_membership_roles
                WHERE organization_id = ? AND customer_id = ? AND role IN ('PAYROLL_APPROVER', 'OPERATIONS')
                """, actors.organizationId(), actors.approverId());

        assertThatThrownBy(() -> executionService.execute(actors.approverId(), approved.id(), 10))
                .isInstanceOf(ApiNotFoundException.class);
        PayrollViews.BatchView executed = executionService.execute(actors.operationsId(), approved.id(), 10);
        assertThat(executed.status()).isEqualTo("COMPLETED");
        assertThat(executed.approvedByCustomerId()).isEqualTo(actors.approverId());
    }

    // ----- R2-06: payroll operation identities are reserved for the payroll workflow -----

    @Test
    void publicTransfersCannotClaimReservedPayrollIdempotencyKeys() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-06 payer");
        UUID payee = createPersonalAccount("R2-06 employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-K", payee, "8.00"));
        UUID itemId = itemIds(approved.id()).getFirst();
        String payrollKey = "payroll-" + approved.id() + "-" + itemId;

        assertThatThrownBy(() -> transferService.transfer(actors.makerId(), payer, payee, new Money(800L), payrollKey))
                .isInstanceOf(TransferException.class)
                .satisfies(exception -> assertThat(((TransferException) exception).code())
                        .isEqualTo(TransferErrorCode.INVALID_REQUEST));
        assertThat(count("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?", payrollKey)).isZero();

        PayrollViews.BatchView completed = executionService.execute(actors.approverId(), approved.id(), 10);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isEqualTo(1);
    }

    @Test
    void unclassifiedTransferFailureLeavesTheItemPendingAndStopsExecution() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2 uncertain payer");
        UUID payee = createPersonalAccount("R2 uncertain employee");
        UUID otherPayee = createPersonalAccount("R2 uncertain other employee");
        PayrollViews.BatchView approved = approvedBatch(actors, payer, item("EMP-U", payee, "5.00"));
        payrollService.startProcessing(approved.id(), actors.approverId());
        UUID itemId = itemIds(approved.id()).getFirst();
        // A conflicting receipt under the same operation identity cannot be classified as a business failure.
        UUID makerPersonalSource = accountService.openAccount(actors.makerId(), "R2 conflicting receipt source");
        transferService.transferAndRecord(actors.makerId(), makerPersonalSource, otherPayee, new Money(100L),
                "payroll-" + approved.id() + "-" + itemId, receipt -> {
                });

        assertThatThrownBy(() -> executionService.execute(actors.approverId(), approved.id(), 10))
                .isInstanceOf(PayrollException.class)
                .satisfies(exception -> assertThat(((PayrollException) exception).code())
                        .isEqualTo("TRANSFER_EXECUTION_ERROR"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payroll_item_results WHERE item_id = ?",
                String.class, itemId)).isEqualTo("PENDING");
        assertThat(batchStatus(approved.id())).isEqualTo("PROCESSING");
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ? AND target_account_id = ?",
                payer, payee)).isZero();
    }

    // ----- R2-07: non-members cannot learn batch state through decision endpoints -----

    @Test
    void nonMembersCannotProbeBatchStateThroughApprovalOrRejection() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2-07 payer");
        UUID payee = createPersonalAccount("R2-07 employee");
        PayrollViews.BatchView draft = createDraft(actors, payer, item("EMP-1", payee, "1.00"));
        UUID outsider = identityService.register("r2-outsider-" + UUID.randomUUID() + "@example.test", PASSWORD,
                "Outside Customer").id();

        assertThatThrownBy(() -> payrollService.approve(outsider, draft.id())).isInstanceOf(ApiNotFoundException.class);
        assertThatThrownBy(() -> payrollService.reject(outsider, draft.id(), "probe"))
                .isInstanceOf(ApiNotFoundException.class);
        assertThatThrownBy(() -> payrollService.approve(actors.approverId(), draft.id()))
                .isInstanceOf(PayrollException.class).hasMessageContaining("submitted");
    }

    // ----- Concurrency and malformed input gaps in the Round 1 suite -----

    @Test
    void concurrentBatchesSharingOnePayerNeverOverdrawIt() throws Exception {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2 shared payer");
        UUID payee = createPersonalAccount("R2 shared employee");
        List<PayrollService.NewPayrollItem> first = new ArrayList<>();
        List<PayrollService.NewPayrollItem> second = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            first.add(item("A-" + index, payee, "20.00"));
            second.add(item("B-" + index, payee, "20.00"));
        }
        PayrollViews.BatchView batchA = approvedBatch(actors, payer, first.toArray(PayrollService.NewPayrollItem[]::new));
        PayrollViews.BatchView batchB = approvedBatch(actors, payer, second.toArray(PayrollService.NewPayrollItem[]::new));

        List<PayrollViews.BatchView> results = concurrently(
                () -> executionService.execute(actors.approverId(), batchA.id(), 1_000),
                () -> executionService.execute(actors.operationsId(), batchB.id(), 1_000));

        int succeeded = results.stream().mapToInt(PayrollViews.BatchView::succeededCount).sum();
        int failed = results.stream().mapToInt(PayrollViews.BatchView::failedCount).sum();
        assertThat(succeeded).isEqualTo(50);
        assertThat(failed).isEqualTo(10);
        assertThat(results).allSatisfy(batch -> assertThat(batch.pendingCount()).isZero());
        assertThat(balance(payer)).isZero();
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isEqualTo(50);
        assertThat(count("""
                SELECT count(*) FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id
                WHERE i.batch_id IN (?, ?) AND r.status = 'FAILED' AND r.error_code <> 'INSUFFICIENT_FUNDS'
                """, batchA.id(), batchB.id())).isZero();
        assertThat(projectionMismatches(payer, payee)).isZero();
    }

    @Test
    void overlappingExecutionsOfOneBatchPayEachItemExactlyOnce() throws Exception {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2 overlap payer");
        UUID payee = createPersonalAccount("R2 overlap employee");
        List<PayrollService.NewPayrollItem> items = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            items.add(item("OV-" + index, payee, "2.50"));
        }
        PayrollViews.BatchView approved = approvedBatch(actors, payer, items.toArray(PayrollService.NewPayrollItem[]::new));

        concurrently(() -> executionService.execute(actors.approverId(), approved.id(), 1_000),
                () -> executionService.execute(actors.operationsId(), approved.id(), 1_000));
        PayrollViews.BatchView finished = executionService.execute(actors.approverId(), approved.id(), 1_000);

        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.succeededCount()).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM transfers WHERE source_account_id = ?", payer)).isEqualTo(40);
        assertThat(count("SELECT count(DISTINCT transfer_id) FROM payroll_item_results r "
                + "JOIN payroll_items i ON i.id = r.item_id WHERE i.batch_id = ?", approved.id())).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM idempotency_records WHERE owner_customer_id = ? AND idempotency_key LIKE ?",
                actors.makerId(), "payroll-" + approved.id() + "-%")).isEqualTo(40);
        assertThat(balance(payer)).isEqualTo(90_000L);
        assertThat(projectionMismatches(payer, payee)).isZero();
    }

    @Test
    void payrollRejectsZeroNonCanonicalAndOverflowingAmounts() {
        Actors actors = createActors();
        UUID payer = accountService.openOrganizationAccount(actors.organizationId(), "R2 amount payer");
        UUID payee = createPersonalAccount("R2 amount employee");
        PayrollViews.BatchView draft = payrollService.createDraft(actors.makerId(), actors.organizationId(),
                "R2 amount validation", payer);

        for (String amount : List.of("0.00", "1.5", "-1.00", "1.001", "92233720368547758.08")) {
            assertThatThrownBy(() -> payrollService.addItems(actors.makerId(), draft.id(),
                    List.of(item("EMP-" + amount, payee, amount))))
                    .as("amount %s", amount).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> payrollService.addItems(actors.makerId(), draft.id(), List.of(
                item("EMP-MAX", payee, "92233720368547758.07"), item("EMP-ONE", payee, "0.01"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds");
        assertThat(payrollService.get(actors.makerId(), draft.id()).itemCount()).isZero();
    }

    // ----- fixtures -----

    private Actors createActors() {
        var maker = identityService.register("r2-maker-" + UUID.randomUUID() + "@example.test", PASSWORD, "R2 Maker");
        var approver = identityService.register("r2-approver-" + UUID.randomUUID() + "@example.test", PASSWORD,
                "R2 Approver");
        var operations = identityService.register("r2-operations-" + UUID.randomUUID() + "@example.test", PASSWORD,
                "R2 Operations");
        var admin = identityService.register("r2-admin-" + UUID.randomUUID() + "@example.test", PASSWORD,
                "R2 Organization Admin");
        var organization = organizationService.create(admin.id(), "R2 Synthetic Payroll "
                + UUID.randomUUID().toString().substring(0, 8));
        organizationService.addMember(admin.id(), organization.id(), maker.email(), List.of(OrganizationRole.PAYROLL_MAKER));
        organizationService.addMember(admin.id(), organization.id(), approver.email(),
                List.of(OrganizationRole.PAYROLL_APPROVER, OrganizationRole.OPERATIONS));
        organizationService.addMember(admin.id(), organization.id(), operations.email(),
                List.of(OrganizationRole.OPERATIONS));
        return new Actors(organization.id(), maker.id(), approver.id(), operations.id());
    }

    private PayrollService.NewPayrollItem item(String reference, UUID payee, String amount) {
        return new PayrollService.NewPayrollItem(reference, "Employee " + reference, payee, amount);
    }

    private PayrollViews.BatchView createDraft(Actors actors, UUID payer, PayrollService.NewPayrollItem... items) {
        PayrollViews.BatchView draft = payrollService.createDraft(actors.makerId(), actors.organizationId(),
                "R2 Payroll " + UUID.randomUUID(), payer);
        return payrollService.addItems(actors.makerId(), draft.id(), List.of(items));
    }

    private PayrollViews.BatchView approvedBatch(Actors actors, UUID payer, PayrollService.NewPayrollItem... items) {
        PayrollViews.BatchView draft = createDraft(actors, payer, items);
        payrollService.submit(actors.makerId(), draft.id());
        return payrollService.approve(actors.approverId(), draft.id());
    }

    private UUID createPersonalAccount(String name) {
        UUID customerId = identityService.register("r2-employee-" + UUID.randomUUID() + "@example.test", PASSWORD,
                name).id();
        return accountService.openAccount(customerId, name + " account");
    }

    private List<UUID> itemIds(UUID batchId) {
        return jdbcTemplate.query("SELECT id FROM payroll_items WHERE batch_id = ? ORDER BY sequence_no",
                (rs, row) -> rs.getObject(1, UUID.class), batchId);
    }

    private String batchStatus(UUID batchId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payroll_batches WHERE id = ?", String.class, batchId);
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

    private int projectionMismatches(UUID... accountIds) {
        int mismatches = 0;
        for (UUID accountId : accountIds) {
            mismatches += count("""
                    SELECT count(*) FROM ledger_accounts la JOIN account_balance_projection p ON p.ledger_account_id = la.id
                    WHERE la.customer_account_id = ? AND p.balance_minor <> (
                        SELECT COALESCE(sum(CASE WHEN posting.side = la.normal_side THEN posting.amount_minor
                                                 ELSE -posting.amount_minor END), 0)
                        FROM postings posting WHERE posting.ledger_account_id = la.id)
                    """, accountId);
        }
        return mismatches;
    }

    @SafeVarargs
    private List<PayrollViews.BatchView> concurrently(java.util.concurrent.Callable<PayrollViews.BatchView>... calls)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(calls.length);
        try {
            List<Future<PayrollViews.BatchView>> futures = new ArrayList<>();
            for (var call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<PayrollViews.BatchView> results = new ArrayList<>();
            for (Future<PayrollViews.BatchView> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
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
                        SELECT 1 FROM pg_stat_activity WHERE application_name = ? AND wait_event_type = 'Lock'
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
                throw new IllegalStateException("Timed out waiting for the concurrent test gate.");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test was interrupted.", exception);
        }
    }

    private record Actors(UUID organizationId, UUID makerId, UUID approverId, UUID operationsId) {
    }
}
