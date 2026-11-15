package nz.ac.ledgercore.clearing;

import static nz.ac.ledgercore.clearing.ClearingTestFixture.RESERVE;
import static nz.ac.ledgercore.clearing.ClearingTestFixture.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import nz.ac.ledgercore.clearing.ClearingDtos.Callback;
import nz.ac.ledgercore.events.OutboxDispatcher;
import nz.ac.ledgercore.reconciliation.ReconciliationRunResult;
import nz.ac.ledgercore.reconciliation.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Round 4 R4-01, real database fault: the after-commit settlement waits on a reserve row lock held by another session,
 * and that waiting session is terminated with pg_terminate_backend (what a database restart or failover does). Spring
 * swallows the listener's exception and the callback is still acknowledged, which on Round 3 code lost the settlement for
 * good. The committed outbox work item must now complete it exactly once.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClearingEffectFastPathFailurePostgresIntegrationTests {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ClearingCallbackService callbacks;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    ReconciliationService reconciliation;

    @Test
    void terminatedSettlementSessionStillAcknowledgesButLeavesDurableWorkThatCompletesOnce() throws Exception {
        ClearingTestFixture fixture = new ClearingTestFixture(port, jdbc, "R4 terminated session");
        UUID payment = fixture.approvedPayment("4.56", "SIM-SETTLE");
        long reserveBefore = fixture.ledgerMinor(RESERVE);
        Callback settled = fixture.callback(payment, 1, "SETTLED");
        String eventKey = "clearing.callback.accepted:" + settled.callbackId();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (Statement statement = holder.createStatement()) {
                statement.execute("SELECT id FROM ledger_accounts WHERE id = '" + RESERVE + "' FOR UPDATE");
                try (ResultSet pid = statement.executeQuery("SELECT pg_backend_pid()")) {
                    pid.next();
                    holderPid = pid.getInt(1);
                }
            }
            Future<Integer> terminated = pool.submit(() -> terminateLockWaiter(holderPid));

            String result = callbacks.receive(settled, bytes(settled)).result();

            assertThat(terminated.get(30, TimeUnit.SECONDS)).as("a settlement session was terminated").isPositive();
            assertThat(result).isEqualTo("ACCEPTED");
            holder.rollback();
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(fixture.journals(payment, "settlement")).isZero();
        assertThat(fixture.paymentStatus(payment)).isEqualTo("PENDING_CLEARING/SETTLED");
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_key = ?", String.class, eventKey))
                .isEqualTo("PENDING");
        // R4-03: the owed settlement is visible to an independent reconciliation while the payment is not COMPLETED.
        ReconciliationRunResult pending = reconciliation.reconcileOrganization(fixture.operations.customerId,
                fixture.organizationId, 50);
        assertThat(pending.findings()).as(pending.findings().toString())
                .anySatisfy(finding -> {
                    assertThat(finding.category()).isEqualTo("CLEARING_FINANCIAL_EFFECT_PENDING");
                    assertThat(finding.resourceId()).isEqualTo(payment);
                });

        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)
                && !"DELIVERED".equals(jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_key = ?",
                        String.class, eventKey))) {
            if (!dispatcher.dispatchOne()) {
                Thread.sleep(200);
            }
        }

        assertThat(fixture.journals(payment, "settlement")).isEqualTo(1);
        assertThat(fixture.paymentStatus(payment)).isEqualTo("COMPLETED/SETTLED");
        assertThat(fixture.ledgerMinor(RESERVE)).isEqualTo(reserveBefore - 456);
        assertThat(fixture.projectionMinor(RESERVE)).isEqualTo(fixture.ledgerMinor(RESERVE));
    }

    private int terminateLockWaiter(int holderPid) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(25);
        while (Instant.now().isBefore(deadline)) {
            Integer victim = jdbc.query("""
                    SELECT pid FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> ? AND wait_event_type = 'Lock'
                      AND query LIKE 'SELECT id FROM ledger_accounts WHERE id = %FOR UPDATE%'
                    LIMIT 1
                    """, rs -> rs.next() ? rs.getInt(1) : null, holderPid);
            if (victim != null) {
                jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, victim);
                return victim;
            }
            Thread.sleep(50);
        }
        return -1;
    }
}
