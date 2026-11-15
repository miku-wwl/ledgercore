package nz.ac.ledgercore.clearing;

import static nz.ac.ledgercore.clearing.ClearingTestFixture.PAYABLE;
import static nz.ac.ledgercore.clearing.ClearingTestFixture.RESERVE;
import static nz.ac.ledgercore.clearing.ClearingTestFixture.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import nz.ac.ledgercore.clearing.ClearingDtos.Callback;
import nz.ac.ledgercore.reconciliation.ReconciliationFinding;
import nz.ac.ledgercore.reconciliation.ReconciliationRunResult;
import nz.ac.ledgercore.reconciliation.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Round 4 / E7 negative fixtures with every production constraint and trigger enabled. For each registered anomaly the
 * test either proves the database refuses to create it (the valid data stays byte-for-byte the same) or creates the
 * recoverable state legitimately and proves reconciliation reports it. Nothing disables a trigger.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgercore.clearing.effect.fast-path-enabled=false")
class ReconciliationNegativeFixturesPostgresIntegrationTests {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ClearingCallbackService callbacks;

    @Autowired
    ReconciliationService reconciliation;

    @Test
    void corruptPaymentAndLedgerStatesAreRefusedByTheDatabaseWithoutTouchingValidRows() throws Exception {
        ClearingTestFixture fixture = new ClearingTestFixture(port, jdbc, "R4 E7 negative");
        UUID payment = fixture.approvedPayment("6.00", "SIM-SETTLE");
        UUID otherFixtureAccount = new ClearingTestFixture(port, jdbc, "R4 E7 other tenant").accountId;
        String before = fingerprint(payment);

        // Missing booking: a pending payment must reference its booking journal.
        assertRefused(() -> jdbc.update("UPDATE corporate_payment_intents SET booking_journal_entry_id = NULL WHERE id = ?",
                payment), "ck_corporate_payment_booking_metadata");
        // Altered approved beneficiary or amount after submission.
        assertRefused(() -> jdbc.update("UPDATE corporate_payment_intents SET beneficiary_account_reference = 'NZ-FORGED' WHERE id = ?",
                payment), "immutable");
        assertRefused(() -> jdbc.update("UPDATE corporate_payment_intents SET amount_minor = amount_minor + 1 WHERE id = ?",
                payment), "immutable");
        // Cross-tenant source: the source account must belong to the payment's organization.
        assertRefused(() -> jdbc.update("UPDATE corporate_payment_intents SET source_account_id = ? WHERE id = ?",
                otherFixtureAccount, payment), "Submitted corporate payment content is immutable");
        // Completed without a settlement journal / settled without a SETTLED outcome.
        assertRefused(() -> jdbc.update("""
                UPDATE corporate_payment_intents SET status = 'COMPLETED', completed_at = clock_timestamp() WHERE id = ?
                """, payment), "ck_corporate_payment_terminal_journals");
        assertRefused(() -> jdbc.update("""
                UPDATE corporate_payment_intents SET status = 'FAILED', completed_at = clock_timestamp() WHERE id = ?
                """, payment), "ck_corporate_payment_terminal_journals");
        // A settlement journal with the wrong amount, and a refund for a payment that was not rejected.
        assertRefused(() -> forgeJournal(payment, "settlement", "CLEARING_SETTLEMENT", PAYABLE, RESERVE, 599),
                "Clearing settlement is not linked to a verified settled payment");
        assertRefused(() -> forgeJournal(payment, "refund", "CORPORATE_PAYMENT_REFUND", PAYABLE,
                depositLedger(fixture.accountId), 600), "Refund is not linked to a definitive pre-settlement rejection");
        // An external outcome cannot move backwards or flip from SETTLED to REJECTED.
        Callback settled = fixture.callback(payment, 1, "SETTLED");
        assertThat(callbacks.receive(settled, bytes(settled)).result()).isEqualTo("ACCEPTED");
        String afterCallback = fingerprint(payment);
        assertRefused(() -> jdbc.update("UPDATE corporate_payment_intents SET external_status = 'REJECTED' WHERE id = ?",
                payment), "Illegal external clearing transition");
        assertRefused(() -> jdbc.update("UPDATE corporate_payment_intents SET external_status = 'PENDING' WHERE id = ?",
                payment), "Illegal external clearing transition");
        // A second settlement attempt (a forged duplicate journal) is refused even while one is legitimately owed.
        assertRefused(() -> forgeJournal(payment, "settlement", "CLEARING_SETTLEMENT", PAYABLE, RESERVE, 600),
                "Clearing settlement is not linked to a verified settled payment");
        // Duplicate durable event identity and history deletion.
        assertRefused(() -> jdbc.update("""
                INSERT INTO outbox_events (id, event_key, event_type, aggregate_type, aggregate_id, payload)
                SELECT gen_random_uuid(), event_key, event_type, aggregate_type, aggregate_id, payload
                FROM outbox_events WHERE event_key = ?
                """, "clearing.callback.accepted:" + settled.callbackId()), "duplicate key");
        assertRefused(() -> jdbc.update("DELETE FROM clearing_callback_inbox WHERE operation_id = ?", payment), "append-only");
        assertRefused(() -> jdbc.update("DELETE FROM journal_entries WHERE reference = ?",
                "corporate-payment:" + payment + ":booking"), "append-only");
        assertThat(fingerprint(payment)).isEqualTo(afterCallback);
        assertThat(before).isNotEqualTo(afterCallback);
        assertThat(fixture.journals(payment, "settlement")).isZero();
    }

    @Test
    void corruptPayrollHistoryIsRefusedAndDeadWorkIsDetected() throws Exception {
        ClearingTestFixture fixture = new ClearingTestFixture(port, jdbc, "R4 E7 payroll");
        ClearingTestFixture.Session payrollMaker = fixture.new Session("R4 E7 payroll maker");
        ClearingTestFixture.Session payrollApprover = fixture.new Session("R4 E7 payroll approver");
        ClearingTestFixture.Session employee = fixture.new Session("R4 E7 employee");
        assertThat(fixture.admin.call("POST", "/api/organizations/" + fixture.organizationId + "/members", "{\"email\":\""
                + payrollMaker.email + "\",\"roles\":[\"PAYROLL_MAKER\"]}").statusCode()).isEqualTo(201);
        assertThat(fixture.admin.call("POST", "/api/organizations/" + fixture.organizationId + "/members", "{\"email\":\""
                + payrollApprover.email + "\",\"roles\":[\"PAYROLL_APPROVER\"]}").statusCode()).isEqualTo(201);
        UUID payee = UUID.fromString(ClearingTestFixture.capture("id", employee.call("POST", "/api/accounts",
                "{\"displayName\":\"E7 salary\"}").body()));
        UUID batch = UUID.fromString(ClearingTestFixture.capture("id", payrollMaker.call("POST",
                "/api/organizations/" + fixture.organizationId + "/payroll/batches",
                "{\"name\":\"E7 batch\",\"payerAccountId\":\"" + fixture.accountId + "\"}").body()));
        assertThat(payrollMaker.call("POST", "/api/payroll/batches/" + batch + "/items",
                "{\"items\":[{\"employeeReference\":\"E7-1\",\"employeeName\":\"E7 One\",\"payeeAccountId\":\"" + payee
                        + "\",\"amount\":\"1.00\"},{\"employeeReference\":\"E7-2\",\"employeeName\":\"E7 Two\","
                        + "\"payeeAccountId\":\"" + payee + "\",\"amount\":\"2.00\"}]}").statusCode()).isEqualTo(200);
        assertThat(payrollMaker.call("POST", "/api/payroll/batches/" + batch + "/submit", null).statusCode()).isEqualTo(200);
        assertThat(payrollApprover.call("POST", "/api/payroll/batches/" + batch + "/approve", null).statusCode())
                .isEqualTo(200);
        String before = payrollFingerprint(batch);

        // Missing result, terminal batch with pending results, altered approved item.
        assertRefused(() -> jdbc.update("DELETE FROM payroll_item_results WHERE batch_id = ?", batch), "retained");
        assertRefused(() -> jdbc.update("UPDATE payroll_batches SET status = 'COMPLETED', completed_at = clock_timestamp() WHERE id = ?",
                batch), "Illegal payroll batch transition");
        assertRefused(() -> jdbc.update("UPDATE payroll_items SET amount_minor = amount_minor + 1 WHERE batch_id = ?", batch),
                "immutable");
        assertRefused(() -> jdbc.update("UPDATE payroll_items SET payee_account_id = ? WHERE batch_id = ?", fixture.accountId,
                batch), "immutable");
        assertThat(payrollFingerprint(batch)).isEqualTo(before);

        // A dead-lettered work item is a recoverable state; reconciliation must report it.
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, event_key, event_type, aggregate_type, aggregate_id, organization_id, payload)
                VALUES (?, ?, 'e7.fixture', 'E7_FIXTURE', ?, ?, '{}'::jsonb)
                """, event, "e7.fixture:" + event, event, fixture.organizationId);
        jdbc.update("UPDATE outbox_events SET status = 'PROCESSING', attempt_count = 5, lease_until = clock_timestamp() WHERE id = ?", event);
        jdbc.update("UPDATE outbox_events SET status = 'DEAD', lease_until = NULL, last_error = 'E7 fixture' WHERE id = ?", event);
        ReconciliationRunResult run = reconciliation.reconcileOrganization(fixture.operations.customerId,
                fixture.organizationId, 50);
        List<ReconciliationFinding> dead = run.findings().stream().filter(finding -> "DEAD_OUTBOX_EVENT".equals(finding.category())
                && finding.resourceId().equals(event)).toList();
        assertThat(dead).hasSize(1);
        assertThat(run.findings()).noneSatisfy(finding -> assertThat(finding.category())
                .isIn("PAYROLL_ITEM_TRANSFER_MISMATCH", "PAYROLL_BATCH_TERMINAL_STATE_MISMATCH"));
    }

    @Test
    void concurrentReconciliationRunsKeepOneOpenExceptionPerAnomalyAndNeverLeakToAnotherTenant() throws Exception {
        ClearingTestFixture fixture = new ClearingTestFixture(port, jdbc, "R4 E7 concurrent graders");
        ClearingTestFixture other = new ClearingTestFixture(port, jdbc, "R4 E7 other graders");
        UUID payment = fixture.approvedPayment("1.11", "SIM-SETTLE");
        Callback settled = fixture.callback(payment, 1, "SETTLED");
        assertThat(callbacks.receive(settled, bytes(settled)).result()).isEqualTo("ACCEPTED");

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<ReconciliationRunResult>> runs = new java.util.ArrayList<>();
            for (int grader = 0; grader < 4; grader++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return reconciliation.reconcileOrganization(fixture.operations.customerId, fixture.organizationId, 50);
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<ReconciliationRunResult> run : runs) {
                assertThat(run.get(60, java.util.concurrent.TimeUnit.SECONDS).findings())
                        .anySatisfy(finding -> assertThat(finding.category()).isEqualTo("CLEARING_FINANCIAL_EFFECT_PENDING"));
            }
        }
        finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reconciliation_exceptions
                WHERE organization_id = ? AND category = 'CLEARING_FINANCIAL_EFFECT_PENDING' AND status <> 'RESOLVED'
                """, Integer.class, fixture.organizationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_exceptions WHERE organization_id = ?",
                Integer.class, other.organizationId)).isZero();
        assertThat(reconciliation.reconcileOrganization(other.operations.customerId, other.organizationId, 50).findings())
                .noneSatisfy(finding -> assertThat(finding.resourceId()).isEqualTo(payment));
        assertThatThrownBy(() -> reconciliation.reconcileOrganization(other.operations.customerId, fixture.organizationId, 50))
                .isInstanceOf(nz.ac.ledgercore.api.ApiNotFoundException.class);
    }

    private void forgeJournal(UUID payment, String action, String type, UUID debit, UUID credit, long amountMinor) {
        transactions.executeWithoutResult(status -> {
            UUID entry = UUID.randomUUID();
            jdbc.update("INSERT INTO journal_entries (id, entry_type, currency, reference) VALUES (?, ?, 'NZD', ?)", entry, type,
                    "corporate-payment:" + payment + ":" + action);
            jdbc.update("INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency) VALUES (?, ?, ?, 'DEBIT', ?, 'NZD')",
                    UUID.randomUUID(), entry, debit, amountMinor);
            jdbc.update("INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency) VALUES (?, ?, ?, 'CREDIT', ?, 'NZD')",
                    UUID.randomUUID(), entry, credit, amountMinor);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        });
    }

    private UUID depositLedger(UUID accountId) {
        return jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE customer_account_id = ? AND kind = 'CUSTOMER_DEPOSIT'",
                UUID.class, accountId);
    }

    private void assertRefused(Runnable mutation, String expectedFragment) {
        assertThatThrownBy(mutation::run).as(expectedFragment).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining(expectedFragment);
    }

    private String fingerprint(UUID payment) {
        return jdbc.queryForObject("""
                SELECT md5(concat_ws('|',
                    (SELECT row_to_json(p)::text FROM corporate_payment_intents p WHERE p.id = ?),
                    (SELECT count(*) || ':' || COALESCE(sum(amount_minor), 0) FROM postings),
                    (SELECT count(*) FROM journal_entries),
                    (SELECT count(*) FROM outbox_events),
                    (SELECT count(*) FROM clearing_callback_inbox)))
                """, String.class, payment);
    }

    private String payrollFingerprint(UUID batch) {
        return jdbc.queryForObject("""
                SELECT md5(concat_ws('|',
                    (SELECT row_to_json(b)::text FROM payroll_batches b WHERE b.id = ?),
                    (SELECT string_agg(row_to_json(i)::text, ',' ORDER BY i.id) FROM payroll_items i WHERE i.batch_id = ?),
                    (SELECT string_agg(row_to_json(r)::text, ',' ORDER BY r.item_id) FROM payroll_item_results r WHERE r.batch_id = ?)))
                """, String.class, batch, batch, batch);
    }
}
