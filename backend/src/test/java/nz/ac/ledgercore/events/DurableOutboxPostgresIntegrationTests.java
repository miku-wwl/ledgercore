package nz.ac.ledgercore.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.MeterRegistry;
import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.identity.IdentityService;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.operations.OutboxOperationsService;
import nz.ac.ledgercore.organizations.OrganizationRole;
import nz.ac.ledgercore.organizations.OrganizationService;
import nz.ac.ledgercore.transfers.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.slf4j.MDC;

@ActiveProfiles("test")
@SpringBootTest
class DurableOutboxPostgresIntegrationTests {

    private static final String PASSWORD = "Outbox-Integration-Password!";

    @Autowired
    DurableEventStore eventStore;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    OutboxOperationsService operationsService;

    @Autowired
    IdentityService identityService;

    @Autowired
    OrganizationService organizationService;

    @Autowired
    AccountService accountService;

    @Autowired
    TransferService transferService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void retriesExpiredLeasesIdempotentlyAndAllowsAuditedDeadLetterRecovery() {
        var admin = identityService.register(email("admin"), PASSWORD, "Outbox Test Admin");
        var organization = organizationService.create(admin.id(), "Outbox Test " + UUID.randomUUID());
        var operations = identityService.register(email("operations"), PASSWORD, "Outbox Test Operations");
        organizationService.addMember(admin.id(), organization.id(), operations.email(), List.of(OrganizationRole.OPERATIONS));
        drainPendingEvents();

        UUID aggregateId = UUID.randomUUID();
        String key = "round3.outbox.retry:" + UUID.randomUUID();
        UUID eventId = eventStore.enqueue(key, "round3.outbox.retry", "TEST", aggregateId, admin.id(),
                organization.id(), Map.of("value", "immutable"));

        OutboxEvent firstClaim = eventStore.claimNext();
        assertThat(firstClaim).isNotNull();
        assertThat(firstClaim.id()).isEqualTo(eventId);
        assertThat(firstClaim.attemptCount()).isEqualTo(1);
        eventStore.recordConsumerReceipt(firstClaim, "durable-fact-observer-v1");
        jdbcTemplate.update("UPDATE outbox_events SET lease_until = clock_timestamp() - interval '1 second' WHERE id = ?",
                eventId);

        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(status(eventId)).isEqualTo("DELIVERED");
        assertThat(attemptCount(eventId)).isEqualTo(2);
        assertThat(receiptCount(eventId, "durable-fact-observer-v1")).isEqualTo(1);
        double duplicateReceiptsBefore = meterRegistry.counter("ledgercore.events.duplicate.receipts").count();
        eventStore.recordConsumerReceipt(firstClaim, "durable-fact-observer-v1");
        assertThat(receiptCount(eventId, "durable-fact-observer-v1")).isEqualTo(1);
        assertThat(meterRegistry.counter("ledgercore.events.duplicate.receipts").count())
                .isEqualTo(duplicateReceiptsBefore + 1);

        UUID deadEventId = eventStore.enqueue("round3.outbox.dead:" + UUID.randomUUID(), "round3.outbox.dead",
                "TEST", UUID.randomUUID(), admin.id(), organization.id(), Map.of("failure", true));
        OutboxDispatcher failingDispatcher = new OutboxDispatcher(eventStore, List.of(new OutboxEventHandler() {
            @Override
            public boolean supports(OutboxEvent event) {
                return event.eventType().equals("round3.outbox.dead");
            }

            @Override
            public void handle(OutboxEvent event) {
                throw new IllegalStateException("simulated downstream outage");
            }
        }));
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(failingDispatcher.dispatchOne()).isTrue();
            assertThat(attemptCount(deadEventId)).isEqualTo(attempt);
            if (attempt < 5) {
                assertThat(status(deadEventId)).isEqualTo("PENDING");
                jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = clock_timestamp() WHERE id = ?",
                        deadEventId);
            }
        }
        assertThat(status(deadEventId)).isEqualTo("DEAD");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE outbox_events SET payload = '{}'::jsonb WHERE id = ?",
                deadEventId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM outbox_events WHERE id = ?", deadEventId))
                .isInstanceOf(DataAccessException.class);

        var retry = operationsService.retry(operations.id(), organization.id(), deadEventId);
        assertThat(retry.status()).isEqualTo("PENDING");
        assertThat(retry.previousAttemptCount()).isEqualTo(5);
        assertThat(status(deadEventId)).isEqualTo("PENDING");
        assertThat(attemptCount(deadEventId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit_events
                WHERE actor_customer_id = ? AND action = 'OUTBOX_MANUAL_RETRY' AND resource_id = ?
                """, Integer.class, operations.id(), deadEventId)).isEqualTo(1);
    }

    @Test
    void concurrentPublishersAndDispatchersDeliverEveryEventOnce() throws Exception {
        var admin = identityService.register(email("concurrent-admin"), PASSWORD, "Outbox Concurrent Admin");
        var organization = organizationService.create(admin.id(), "Outbox Concurrent " + UUID.randomUUID());
        drainPendingEvents();

        int eventCount = 24;
        String eventKeyPrefix = "round3.outbox.concurrent:" + UUID.randomUUID() + ":";
        var publishStart = new CountDownLatch(1);
        var eventIds = new java.util.ArrayList<UUID>();
        try (var publishers = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<UUID>>();
            for (int index = 0; index < eventCount; index++) {
                int eventIndex = index;
                futures.add(publishers.submit(() -> {
                    publishStart.await();
                    return eventStore.enqueue(eventKeyPrefix + eventIndex, "round3.outbox.concurrent", "TEST",
                            UUID.randomUUID(), admin.id(), organization.id(), Map.of("index", eventIndex));
                }));
            }
            publishStart.countDown();
            for (var future : futures) {
                eventIds.add(future.get(30, TimeUnit.SECONDS));
            }
        }
        assertThat(eventIds).hasSize(eventCount).doesNotHaveDuplicates();

        var consumeStart = new CountDownLatch(1);
        try (var consumers = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>(4);
            for (int worker = 0; worker < 4; worker++) {
                futures.add(consumers.submit(() -> {
                    consumeStart.await();
                    for (int attempt = 0; attempt < eventCount; attempt++) {
                        dispatcher.dispatchOne();
                    }
                    return null;
                }));
            }
            consumeStart.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE event_key LIKE ? AND status = 'DELIVERED'
                """, Integer.class, eventKeyPrefix + "%")).isEqualTo(eventCount);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox_consumer_receipts receipt
                JOIN outbox_events event ON event.id = receipt.event_id
                WHERE event.event_key LIKE ? AND receipt.consumer_key = 'durable-fact-observer-v1'
                """, Integer.class, eventKeyPrefix + "%")).isEqualTo(eventCount);
    }

    @Test
    void carriesPersistedCorrelationIdIntoBackgroundHandlerAndRestoresWorkerContext() {
        drainPendingEvents();
        String eventCorrelationId = "e9-trace-" + UUID.randomUUID();
        String previousCorrelationId = MDC.get("correlationId");
        try {
            MDC.put("correlationId", eventCorrelationId);
            UUID eventId = eventStore.enqueue("e9.correlation:" + UUID.randomUUID(), "e9.correlation.test", "TEST",
                    UUID.randomUUID(), null, null, java.util.Map.of("fixture", true));
            MDC.put("correlationId", "outer-worker-context");
            OutboxEvent claimed = eventStore.claimNext();
            assertThat(claimed).isNotNull();
            assertThat(claimed.id()).isEqualTo(eventId);

            AtomicReference<String> observedCorrelationId = new AtomicReference<>();
            OutboxDispatcher correlatedDispatcher = new OutboxDispatcher(eventStore, List.of(new OutboxEventHandler() {
                @Override
                public boolean supports(OutboxEvent event) {
                    return event.eventType().equals("e9.correlation.test");
                }

                @Override
                public void handle(OutboxEvent event) {
                    observedCorrelationId.set(MDC.get("correlationId"));
                }
            }), meterRegistry);
            correlatedDispatcher.dispatchClaimed(claimed);

            assertThat(observedCorrelationId.get()).isEqualTo(eventCorrelationId);
            assertThat(MDC.get("correlationId")).isEqualTo("outer-worker-context");
            assertThat(status(eventId)).isEqualTo("DELIVERED");
        }
        finally {
            if (previousCorrelationId == null) {
                MDC.remove("correlationId");
            }
            else {
                MDC.put("correlationId", previousCorrelationId);
            }
        }
    }

    @Test
    void transferAndItsOutboxFactRollBackTogether() {
        var owner = identityService.register(email("transfer-owner"), PASSWORD, "Outbox Transfer Owner");
        var targetOwner = identityService.register(email("transfer-target"), PASSWORD, "Outbox Transfer Target");
        UUID source = accountService.openAccount(owner.id(), "Outbox rollback source");
        UUID target = accountService.openAccount(targetOwner.id(), "Outbox rollback target");
        String key = "round3-outbox-rollback-" + UUID.randomUUID();
        int journalCount = count("SELECT count(*) FROM journal_entries");
        int postingCount = count("SELECT count(*) FROM postings");
        int transferCount = count("SELECT count(*) FROM transfers");
        long balanceBefore = jdbcTemplate.queryForObject("""
                SELECT p.balance_minor FROM account_balance_projection p
                JOIN ledger_accounts a ON a.id = p.ledger_account_id
                WHERE a.customer_account_id = ? AND a.kind = 'CUSTOMER_DEPOSIT'
                """, Long.class, source);

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_round3_fail_transfer_outbox ON outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS round3_fail_transfer_outbox()");
        jdbcTemplate.execute("""
                CREATE FUNCTION round3_fail_transfer_outbox() RETURNS TRIGGER AS $$
                BEGIN
                    IF NEW.event_type = 'transfer.posted' THEN
                        RAISE EXCEPTION 'injected outbox failure' USING ERRCODE = 'P0001';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER trg_round3_fail_transfer_outbox
                BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION round3_fail_transfer_outbox()
                """);
        try {
            assertThatThrownBy(() -> transferService.transfer(owner.id(), source, target, new Money(500L), key))
                    .isInstanceOf(DataAccessException.class);
        }
        finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_round3_fail_transfer_outbox ON outbox_events");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS round3_fail_transfer_outbox()");
        }

        assertThat(count("SELECT count(*) FROM journal_entries")).isEqualTo(journalCount);
        assertThat(count("SELECT count(*) FROM postings")).isEqualTo(postingCount);
        assertThat(count("SELECT count(*) FROM transfers")).isEqualTo(transferCount);
        assertThat(count("SELECT count(*) FROM idempotency_records WHERE owner_customer_id = ? AND idempotency_key = ?",
                owner.id(), key)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT p.balance_minor FROM account_balance_projection p
                JOIN ledger_accounts a ON a.id = p.ledger_account_id
                WHERE a.customer_account_id = ? AND a.kind = 'CUSTOMER_DEPOSIT'
                """, Long.class, source)).isEqualTo(balanceBefore);
    }

    private void drainPendingEvents() {
        for (int count = 0; count < 10_000 && dispatcher.dispatchOne(); count++) {
            // Keep the test's claim assertions independent of events created by earlier test classes.
        }
    }

    private String email(String role) {
        return "round3-outbox-" + role + "-" + UUID.randomUUID() + "@example.test";
    }

    private String status(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private int attemptCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT attempt_count FROM outbox_events WHERE id = ?", Integer.class,
                eventId);
    }

    private int receiptCount(UUID eventId, String consumerKey) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox_consumer_receipts WHERE event_id = ? AND consumer_key = ?
                """, Integer.class, eventId, consumerKey);
    }

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }
}
