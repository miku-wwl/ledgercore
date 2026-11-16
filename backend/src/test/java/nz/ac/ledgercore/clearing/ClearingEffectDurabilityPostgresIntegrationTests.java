package nz.ac.ledgercore.clearing;

import static nz.ac.ledgercore.clearing.ClearingTestFixture.PAYABLE;
import static nz.ac.ledgercore.clearing.ClearingTestFixture.RESERVE;
import static nz.ac.ledgercore.clearing.ClearingTestFixture.TREASURY;
import static nz.ac.ledgercore.clearing.ClearingTestFixture.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import nz.ac.ledgercore.clearing.ClearingDtos.Callback;
import nz.ac.ledgercore.clearing.ClearingFinancialEffectProcessor.Outcome;
import nz.ac.ledgercore.events.DurableEventStore;
import nz.ac.ledgercore.events.OutboxDispatcher;
import nz.ac.ledgercore.ledger.LedgerWriter;
import nz.ac.ledgercore.ledger.LedgerWriter.LiquidityDirection;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.reconciliation.ReconciliationFinding;
import nz.ac.ledgercore.reconciliation.ReconciliationRunResult;
import nz.ac.ledgercore.reconciliation.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round 4 R4-01/R4-02/R4-03 regression tests. The after-commit fast path is switched off, so every accepted callback
 * deterministically stops in the window that a crash, a swallowed listener exception or a terminated database session
 * produces: the callback, the payment's external status and the outbox event are committed, the settlement is not.
 * Against the Round 3 code base this state was never completed and never reported.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgercore.clearing.effect.fast-path-enabled=false")
class ClearingEffectDurabilityPostgresIntegrationTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClearingCallbackService callbacks;

    @Autowired
    ClearingFinancialEffects effects;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    DurableEventStore eventStore;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    LedgerWriter ledgerWriter;

    @Test
    void settledCallbackWhoseEffectNeverRanIsDetectedThenCompletedOnceFromItsDurableWorkItem() throws Exception {
        ClearingTestFixture fixture = new ClearingTestFixture(port, jdbc, "R4 durable settle");
        UUID payment = fixture.approvedPayment("12.34", "SIM-SETTLE");
        long reserveBefore = fixture.ledgerMinor(RESERVE);
        long payableBefore = fixture.ledgerMinor(PAYABLE);
        long depositBefore = fixture.sourceDepositMinor();
        Callback settled = fixture.callback(payment, 1, "SETTLED");

        assertThat(callbacks.receive(settled, bytes(settled)).result()).isEqualTo("ACCEPTED");

        String eventKey = "clearing.callback.accepted:" + settled.callbackId();
        assertThat(fixture.paymentStatus(payment)).isEqualTo("PENDING_CLEARING/SETTLED");
        assertThat(fixture.journals(payment, "settlement")).isZero();
        assertThat(eventStatus(eventKey)).isEqualTo("PENDING");

        ReconciliationRunResult run = reconciliation.reconcileOrganization(fixture.operations.customerId,
                fixture.organizationId, 50);
        ReconciliationFinding pending = only(run, "CLEARING_FINANCIAL_EFFECT_PENDING", payment);
        assertThat(pending.severity()).isEqualTo("HIGH");
        assertThat(pending.expectedFacts()).containsEntry("requiredEffect", "CLEARING_SETTLEMENT");
        assertThat(pending.observedFacts()).containsEntry("automaticRetryPending", true)
                .containsEntry("terminalCallbackId", settled.callbackId().toString());
        assertThat(resolve(fixture, pending.exceptionId(), "Reviewed the dashboard.").statusCode()).isEqualTo(409);
        assertThat(exceptionStatus(pending.exceptionId())).isEqualTo("OPEN");

        dispatchUntilFinished(eventKey);

        assertThat(eventStatus(eventKey)).isEqualTo("DELIVERED");
        assertThat(receipts(eventKey)).contains(ClearingCallbackEffectOutboxHandler.CONSUMER_KEY);
        assertThat(fixture.paymentStatus(payment)).isEqualTo("COMPLETED/SETTLED");
        assertThat(fixture.journals(payment, "booking")).isEqualTo(1);
        assertThat(fixture.journals(payment, "settlement")).isEqualTo(1);
        assertThat(fixture.journals(payment, "refund")).isZero();
        assertThat(fixture.ledgerMinor(PAYABLE)).isEqualTo(payableBefore - 1234);
        assertThat(fixture.ledgerMinor(RESERVE)).isEqualTo(reserveBefore - 1234);
        assertThat(fixture.sourceDepositMinor()).isEqualTo(depositBefore);
        assertThat(fixture.projectionMinor(RESERVE)).isEqualTo(fixture.ledgerMinor(RESERVE));
        assertThat(fixture.projectionMinor(PAYABLE)).isEqualTo(fixture.ledgerMinor(PAYABLE));

        assertThat(callbacks.receive(settled, bytes(settled)).result()).isEqualTo("DUPLICATE");
        assertThat(effects.applyOwedEffect(payment, "test-replay").outcome()).isEqualTo(Outcome.ALREADY_APPLIED);
        assertThat(fixture.journals(payment, "settlement")).isEqualTo(1);

        HttpResponse<String> resolved = resolve(fixture, pending.exceptionId(), "Durable effect completed; recheck clean.");
        assertThat(resolved.statusCode()).as(resolved.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("""
                SELECT details::text FROM audit_events
                WHERE action = 'RECONCILIATION_EXCEPTION_RESOLVED' AND resource_id = ?
                """, String.class, pending.exceptionId())).contains("INDEPENDENT_SQL_RECHECK", "CLEAN");
        assertThat(jdbc.queryForObject("""
                SELECT actor_customer_id FROM audit_events
                WHERE action = 'RECONCILIATION_EXCEPTION_RESOLVED' AND resource_id = ?
                """, UUID.class, pending.exceptionId())).isEqualTo(fixture.operations.customerId);
        assertThat(findings(reconciliation.reconcileOrganization(fixture.operations.customerId, fixture.organizationId, 50),
                "CLEARING_FINANCIAL_EFFECT_PENDING", payment)).isEmpty();
    }

    @Test
    void rejectedCallbackWhoseRefundNeverRanIsDetectedThenRefundedOnceWithoutSettlement() throws Exception {
        ClearingTestFixture fixture = new ClearingTestFixture(port, jdbc, "R4 durable refund");
        UUID payment = fixture.approvedPayment("7.00", "SIM-REJECT");
        long depositAfterBooking = fixture.sourceDepositMinor();
        Callback rejected = fixture.callback(payment, 1, "REJECTED");

        assertThat(callbacks.receive(rejected, bytes(rejected)).result()).isEqualTo("ACCEPTED");
        assertThat(fixture.paymentStatus(payment)).isEqualTo("PENDING_CLEARING/REJECTED");
        ReconciliationFinding pending = only(reconciliation.reconcileOrganization(fixture.operations.customerId,
                fixture.organizationId, 50), "CLEARING_FINANCIAL_EFFECT_PENDING", payment);
        assertThat(pending.expectedFacts()).containsEntry("requiredEffect", "CORPORATE_PAYMENT_REFUND");

        dispatchUntilFinished("clearing.callback.accepted:" + rejected.callbackId());

        assertThat(fixture.paymentStatus(payment)).isEqualTo("REJECTED/REJECTED");
        assertThat(fixture.journals(payment, "refund")).isEqualTo(1);
        assertThat(fixture.journals(payment, "settlement")).isZero();
        assertThat(fixture.sourceDepositMinor()).isEqualTo(depositAfterBooking + 700);
        assertThat(findings(reconciliation.reconcileOrganization(fixture.operations.customerId, fixture.organizationId, 50),
                "CLEARING_FINANCIAL_EFFECT_PENDING", payment)).isEmpty();
    }

    @Test
    void competingWorkersDuplicateCallbackAndDirectRetryPostExactlyOneSettlement() throws Exception {
        ClearingTestFixture fixture = new ClearingTestFixture(port, jdbc, "R4 competing workers");
        UUID payment = fixture.approvedPayment("3.21", "SIM-SETTLE");
        long reserveBefore = fixture.ledgerMinor(RESERVE);
        Callback settled = fixture.callback(payment, 1, "SETTLED");
        assertThat(callbacks.receive(settled, bytes(settled)).result()).isEqualTo("ACCEPTED");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Object>> work = new ArrayList<>();
            work.add(() -> { start.await(); return dispatcher.dispatchAvailable(100); });
            work.add(() -> { start.await(); return dispatcher.dispatchAvailable(100); });
            work.add(() -> { start.await(); return callbacks.receive(settled, bytes(settled)).result(); });
            work.add(() -> { start.await(); return effects.applyOwedEffect(payment, "test-competitor").outcome(); });
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : work) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        finally {
            pool.shutdownNow();
        }
        dispatchUntilFinished("clearing.callback.accepted:" + settled.callbackId());

        assertThat(fixture.journals(payment, "settlement")).isEqualTo(1);
        assertThat(fixture.paymentStatus(payment)).isEqualTo("COMPLETED/SETTLED");
        assertThat(fixture.ledgerMinor(RESERVE)).isEqualTo(reserveBefore - 321);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clearing_callback_inbox WHERE operation_id = ?",
                Integer.class, payment)).isEqualTo(1);
        UUID eventId = eventId("clearing.callback.accepted:" + settled.callbackId());
        assertThatThrownBy(() -> eventStore.retryDeadLetter(eventId, fixture.organizationId, fixture.operations.customerId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(fixture.journals(payment, "settlement")).isEqualTo(1);
    }

    @Test
    void genuinelyFiniteReserveHoldsTheObligationUntilAuditedReplenishmentAndGovernedRetry() throws Exception {
        ClearingTestFixture fixture = new ClearingTestFixture(port, jdbc, "R4 finite reserve");
        UUID sweep = UUID.randomUUID();
        long swept = fixture.ledgerMinor(RESERVE) - 5_000;
        assertThat(swept).isPositive();
        ledgerWriter.transferReserveLiquidity(sweep, LiquidityDirection.OUT, new Money(swept));
        UUID replenish = UUID.randomUUID();
        try {
            assertThat(fixture.ledgerMinor(RESERVE)).isEqualTo(5_000);
            assertThat(fixture.balanceSheetBalances()).isTrue();
            UUID payment = fixture.approvedPayment("80.00", "SIM-SETTLE");
            assertThat(fixture.ledgerMinor(PAYABLE)).isGreaterThanOrEqualTo(8_000);
            Callback settled = fixture.callback(payment, 1, "SETTLED");
            assertThat(callbacks.receive(settled, bytes(settled)).result()).isEqualTo("ACCEPTED");
            String eventKey = "clearing.callback.accepted:" + settled.callbackId();

            // The durable work item retries with backoff, never posts a partial settlement and never refunds.
            awaitEventStatus(eventKey, "DEAD", Duration.ofSeconds(60));
            assertThat(fixture.journals(payment, "settlement")).isZero();
            assertThat(fixture.journals(payment, "refund")).isZero();
            assertThat(fixture.paymentStatus(payment)).isEqualTo("PENDING_CLEARING/SETTLED");
            assertThat(fixture.ledgerMinor(RESERVE)).isEqualTo(5_000);
            assertThat(fixture.projectionMinor(RESERVE)).isEqualTo(5_000);
            assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE event_key = ?", String.class,
                    eventKey)).isEqualTo("ClearingEffectDeferredException");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM reconciliation_exceptions
                    WHERE operation_id = ? AND category = 'CLEARING_RESERVE_INSUFFICIENT' AND severity = 'CRITICAL'
                      AND status = 'OPEN'
                    """, Integer.class, payment)).isEqualTo(1);
            // Section 18: the Operations console shows the owed effect, the payable still held and the reserve block.
            JsonNode blocked = metrics(fixture);
            assertThat(blocked.path("pendingFinancialEffects").asLong()).isEqualTo(1);
            assertThat(blocked.path("oldestPendingEffectAt").isMissingNode() || blocked.path("oldestPendingEffectAt").isNull()).isFalse();
            assertThat(blocked.path("reserveBlockedSettlements").asLong()).isEqualTo(1);
            assertThat(blocked.path("unresolvedPayableMinor").asLong()).isEqualTo(8_000);
            assertThat(blocked.path("failedEvents").asLong()).isEqualTo(1);
            assertThat(blocked.path("retryingEvents").asLong()).isZero();

            ReconciliationRunResult run = reconciliation.reconcileOrganization(fixture.operations.customerId,
                    fixture.organizationId, 50);
            ReconciliationFinding pending = only(run, "CLEARING_FINANCIAL_EFFECT_PENDING", payment);
            assertThat(pending.severity()).isEqualTo("CRITICAL");
            assertThat(pending.observedFacts()).containsEntry("automaticRetryPending", false)
                    .containsEntry("latestWorkStatus", "DEAD");
            assertThat(run.findings()).anySatisfy(finding -> assertThat(finding.category()).isEqualTo("DEAD_OUTBOX_EVENT"));
            UUID reserveException = jdbc.queryForObject("""
                    SELECT id FROM reconciliation_exceptions
                    WHERE operation_id = ? AND category = 'CLEARING_RESERVE_INSUFFICIENT' AND status = 'OPEN'
                    """, UUID.class, payment);
            assertThat(resolve(fixture, reserveException, "Reserve looks fine now.").statusCode()).isEqualTo(409);
            assertThat(resolve(fixture, pending.exceptionId(), "Reserve looks fine now.").statusCode()).isEqualTo(409);

            // Audited replenishment from the treasury, then one governed retry by an OPERATIONS member.
            ledgerWriter.transferReserveLiquidity(replenish, LiquidityDirection.IN, new Money(10_000));
            assertThat(fixture.ledgerMinor(RESERVE)).isEqualTo(15_000);
            UUID eventId = eventId(eventKey);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Integer> statuses = new ArrayList<>();
            try {
                List<Future<Integer>> retries = new ArrayList<>();
                for (int operator = 0; operator < 2; operator++) {
                    retries.add(pool.submit(() -> {
                        start.await();
                        return fixture.operations.call("POST", "/api/organizations/" + fixture.organizationId
                                + "/operations/outbox/" + eventId + "/retry", null).statusCode();
                    }));
                }
                start.countDown();
                for (Future<Integer> retry : retries) {
                    statuses.add(retry.get(30, TimeUnit.SECONDS));
                }
            }
            finally {
                pool.shutdownNow();
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
            dispatchUntilFinished(eventKey);

            assertThat(eventStatus(eventKey)).isEqualTo("DELIVERED");
            assertThat(fixture.journals(payment, "settlement")).isEqualTo(1);
            assertThat(fixture.paymentStatus(payment)).isEqualTo("COMPLETED/SETTLED");
            assertThat(fixture.ledgerMinor(RESERVE)).isEqualTo(7_000);
            assertThat(fixture.ledgerMinor(PAYABLE)).isEqualTo(fixture.projectionMinor(PAYABLE));
            assertThat(fixture.balanceSheetBalances()).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM audit_events WHERE action = 'OUTBOX_MANUAL_RETRY' AND resource_id = ?
                      AND actor_customer_id = ?
                    """, Integer.class, eventId, fixture.operations.customerId)).isEqualTo(1);
            assertThat(callbacks.receive(settled, bytes(settled)).result()).isEqualTo("DUPLICATE");
            assertThat(fixture.journals(payment, "settlement")).isEqualTo(1);

            assertThat(resolve(fixture, reserveException, "Treasury replenished the reserve; settlement posted once.")
                    .statusCode()).isEqualTo(200);
            assertThat(resolve(fixture, pending.exceptionId(), "Settlement posted once after governed retry.")
                    .statusCode()).isEqualTo(200);
            JsonNode cleared = metrics(fixture);
            assertThat(cleared.path("pendingFinancialEffects").asLong()).isZero();
            assertThat(cleared.path("reserveBlockedSettlements").asLong()).isZero();
            assertThat(cleared.path("unresolvedPayableMinor").asLong()).isZero();
            assertThat(cleared.path("failedEvents").asLong()).isZero();
        }
        finally {
            long treasury = fixture.ledgerMinor(TREASURY);
            if (treasury > 0) {
                ledgerWriter.transferReserveLiquidity(UUID.randomUUID(), LiquidityDirection.IN, new Money(treasury));
            }
        }
        assertThat(fixture.ledgerMinor(TREASURY)).isZero();
        assertThat(fixture.balanceSheetBalances()).isTrue();
    }

    @Test
    void bookedInstructionAcknowledgedWithoutDispatchIsReportedInsteadOfStayingSilentlyPending() throws Exception {
        // This test context runs without the clearing integration (the application default), so the receipt-only
        // fact observer is the only consumer of clearing.instruction.ready (R4-05).
        ClearingTestFixture fixture = new ClearingTestFixture(port, jdbc, "R4 undispatched");
        UUID payment = fixture.approvedPayment("2.22", "SIM-SETTLE");
        String readyKey = "clearing.instruction.ready:" + payment;
        dispatchUntilFinished(readyKey);

        assertThat(eventStatus(readyKey)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM clearing_instructions WHERE operation_id = ?", String.class,
                payment)).isEqualTo("READY");
        assertThat(fixture.paymentStatus(payment)).isEqualTo("PENDING_CLEARING/-");
        ReconciliationFinding undispatched = only(reconciliation.reconcileOrganization(fixture.operations.customerId,
                fixture.organizationId, 50), "CLEARING_INSTRUCTION_NOT_DISPATCHED", payment);
        assertThat(undispatched.severity()).isEqualTo("CRITICAL");
        assertThat(undispatched.observedFacts()).containsEntry("dispatchEventStatus", "DELIVERED")
                .containsEntry("instructionStatus", "READY");
        assertThat(resolve(fixture, undispatched.exceptionId(), "Nothing to see.").statusCode()).isEqualTo(409);
    }

    @Test
    void reserveLiquidityTransfersAreBalancedReplayableAndCannotOverdrawEitherAsset() {
        UUID request = UUID.randomUUID();
        long reserve = currentLedger(RESERVE);
        var first = ledgerWriter.transferReserveLiquidity(request, LiquidityDirection.OUT, new Money(1_00));
        var replay = ledgerWriter.transferReserveLiquidity(request, LiquidityDirection.OUT, new Money(1_00));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.journalEntryId()).isEqualTo(first.journalEntryId());
        assertThat(currentLedger(RESERVE)).isEqualTo(reserve - 100);
        assertThatThrownBy(() -> ledgerWriter.transferReserveLiquidity(request, LiquidityDirection.OUT, new Money(2_00)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledgerWriter.transferReserveLiquidity(UUID.randomUUID(), LiquidityDirection.IN,
                new Money(currentLedger(TREASURY) + 1))).isInstanceOf(nz.ac.ledgercore.ledger.InsufficientLedgerBalanceException.class);
        assertThatThrownBy(() -> ledgerWriter.reverseJournalEntry(first.journalEntryId()))
                .isInstanceOf(IllegalArgumentException.class);
        ledgerWriter.transferReserveLiquidity(UUID.randomUUID(), LiquidityDirection.IN, new Money(1_00));
        assertThat(currentLedger(RESERVE)).isEqualTo(reserve);
        assertThat(currentLedger(TREASURY)).isZero();
        // The database refuses a treasury posting from any other journal type, even a balanced one.
        assertThatThrownBy(() -> jdbc.execute("""
                DO $$
                DECLARE entry UUID := gen_random_uuid();
                BEGIN
                  INSERT INTO journal_entries (id, entry_type, currency, reference) VALUES (entry, 'TRANSFER', 'NZD', 'forged');
                  INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency)
                  VALUES (gen_random_uuid(), entry, '00000000-0000-5000-8000-000000000003', 'DEBIT', 1, 'NZD'),
                         (gen_random_uuid(), entry, '00000000-0000-5000-8000-000000000001', 'CREDIT', 1, 'NZD');
                  SET CONSTRAINTS ALL IMMEDIATE;
                END $$
                """)).hasStackTraceContaining("Only a reserve liquidity transfer may post to the simulation treasury");
    }

    private long currentLedger(UUID ledgerAccountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side THEN p.amount_minor ELSE -p.amount_minor END), 0)
                FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id WHERE la.id = ?
                """, Long.class, ledgerAccountId);
    }

    private JsonNode metrics(ClearingTestFixture fixture) throws Exception {
        HttpResponse<String> response = fixture.operations.call("GET", "/api/organizations/" + fixture.organizationId
                + "/operations/metrics", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> resolve(ClearingTestFixture fixture, UUID exceptionId, String note) throws Exception {
        return fixture.operations.call("POST", "/api/organizations/" + fixture.organizationId
                + "/operations/reconciliation/exceptions/" + exceptionId + "/resolve", "{\"resolution\":\"" + note + "\"}");
    }

    private ReconciliationFinding only(ReconciliationRunResult run, String category, UUID resourceId) {
        List<ReconciliationFinding> matches = findings(run, category, resourceId);
        assertThat(matches).as(run.findings().toString()).hasSize(1);
        return matches.getFirst();
    }

    private List<ReconciliationFinding> findings(ReconciliationRunResult run, String category, UUID resourceId) {
        return run.findings().stream().filter(finding -> finding.category().equals(category)
                && finding.resourceId().equals(resourceId)).toList();
    }

    private void dispatchUntilFinished(String eventKey) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            String status = eventStatus(eventKey);
            if ("DELIVERED".equals(status) || "DEAD".equals(status)) {
                return;
            }
            if (!dispatcher.dispatchOne()) {
                Thread.sleep(200);
            }
        }
        throw new AssertionError("Outbox event did not finish: " + eventKey);
    }

    private void awaitEventStatus(String eventKey, String expected, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (expected.equals(eventStatus(eventKey))) {
                return;
            }
            if (!dispatcher.dispatchOne()) {
                Thread.sleep(250);
            }
        }
        throw new AssertionError("Outbox event " + eventKey + " did not reach " + expected + " but " + eventStatus(eventKey));
    }

    private String eventStatus(String eventKey) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_key = ?", String.class, eventKey);
    }

    private UUID eventId(String eventKey) {
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE event_key = ?", UUID.class, eventKey);
    }

    private List<String> receipts(String eventKey) {
        return jdbc.queryForList("""
                SELECT r.consumer_key FROM outbox_consumer_receipts r JOIN outbox_events e ON e.id = r.event_id
                WHERE e.event_key = ? ORDER BY r.consumer_key
                """, String.class, eventKey);
    }

    private String exceptionStatus(UUID exceptionId) {
        return jdbc.queryForObject("SELECT status FROM reconciliation_exceptions WHERE id = ?", String.class, exceptionId);
    }
}
