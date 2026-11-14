package nz.ac.ledgercore.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Persists immutable business facts and actor-attributed audit rows in the caller's transaction. */
@Service
public class DurableEventStore {

    private static final int MAX_ATTEMPTS = 5;
    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public DurableEventStore(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public UUID enqueue(String eventKey, String eventType, String aggregateType, UUID aggregateId,
            UUID actorCustomerId, UUID organizationId, Map<String, ?> payload) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (id, event_key, event_type, aggregate_type, aggregate_id,
                    actor_customer_id, organization_id, correlation_id, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, eventId, eventKey, eventType, aggregateType, aggregateId, actorCustomerId, organizationId,
                correlationId(), json(payload));
        return eventId;
    }

    @Transactional
    public UUID recordAudit(UUID actorCustomerId, String action, String resourceType, UUID resourceId,
            UUID organizationId, Map<String, ?> details) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO audit_events (id, actor_customer_id, action, resource_type, resource_id,
                    organization_id, correlation_id, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, eventId, actorCustomerId, action, resourceType, resourceId, organizationId, correlationId(),
                json(details));
        return eventId;
    }

    /** Claims one row and commits the lease before invoking a consumer. Concurrent workers use SKIP LOCKED. */
    @Transactional
    public OutboxEvent claimNext() {
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = 'DEAD', lease_until = NULL,
                    last_error = COALESCE(last_error, 'Delivery lease expired after the retry limit.')
                WHERE status = 'PROCESSING' AND lease_until < clock_timestamp() AND attempt_count >= ?
                """, MAX_ATTEMPTS);
        List<OutboxEvent> rows = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id FROM outbox_events
                    WHERE attempt_count < ? AND (
                        (status = 'PENDING' AND next_attempt_at <= clock_timestamp()) OR
                        (status = 'PROCESSING' AND lease_until < clock_timestamp())
                    )
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE outbox_events event
                SET status = 'PROCESSING', attempt_count = event.attempt_count + 1,
                    lease_until = clock_timestamp() + interval '30 seconds'
                FROM candidate
                WHERE event.id = candidate.id
                RETURNING event.id, event.event_key, event.event_type, event.aggregate_type, event.aggregate_id,
                    event.actor_customer_id, event.organization_id, event.correlation_id, event.payload::text,
                    event.attempt_count
                """, (rs, row) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("event_key"),
                rs.getString("event_type"), rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                rs.getObject("actor_customer_id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("correlation_id"), rs.getString("payload"), rs.getInt("attempt_count")), MAX_ATTEMPTS);
        return rows.stream().findFirst().orElse(null);
    }

    @Transactional
    public void markDelivered(UUID eventId) {
        int updated = jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = 'DELIVERED', delivered_at = clock_timestamp(), lease_until = NULL, last_error = NULL
                WHERE id = ? AND status = 'PROCESSING'
                """, eventId);
        if (updated != 1) {
            throw new IllegalStateException("Outbox event delivery lease was lost before completion.");
        }
    }

    @Transactional
    public void markDeliveryFailure(UUID eventId, int attemptCount, String error) {
        String safeError = error == null ? "Unclassified event delivery failure."
                : error.replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(500, error.replaceAll("[\\r\\n\\t]", " ").length()));
        int backoffSeconds = Math.min(30, 1 << Math.min(Math.max(attemptCount - 1, 0), 5));
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = CASE WHEN attempt_count >= ? THEN 'DEAD' ELSE 'PENDING' END,
                    next_attempt_at = clock_timestamp() + make_interval(secs => ?),
                    lease_until = NULL, last_error = ?
                WHERE id = ? AND status = 'PROCESSING'
                """, MAX_ATTEMPTS, backoffSeconds, safeError, eventId);
    }

    @Transactional
    public void recordConsumerReceipt(OutboxEvent event, String consumerKey) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO outbox_consumer_receipts (event_id, consumer_key, result_digest)
                VALUES (?, ?, ?)
                ON CONFLICT (event_id, consumer_key) DO NOTHING
                """, event.id(), consumerKey, sha256(event.payloadJson()));
        if (inserted == 0) {
            meterRegistry.counter("ledgercore.events.duplicate.receipts").increment();
        }
    }

    @Transactional(readOnly = true)
    public List<OutboxStatusView> recentOutbox(int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        return jdbcTemplate.query("""
                SELECT id, event_key, event_type, aggregate_type, aggregate_id, actor_customer_id,
                       organization_id, status, attempt_count, last_error, created_at, delivered_at
                FROM outbox_events ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new OutboxStatusView(rs.getObject("id", UUID.class), rs.getString("event_key"),
                rs.getString("event_type"), rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                rs.getObject("actor_customer_id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("status"), rs.getInt("attempt_count"), rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("delivered_at") == null ? null
                        : rs.getTimestamp("delivered_at").toInstant()), safeLimit);
    }

    @Transactional(readOnly = true)
    public List<OutboxStatusView> outboxForOrganization(UUID organizationId, int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        return jdbcTemplate.query("""
                SELECT id, event_key, event_type, aggregate_type, aggregate_id, actor_customer_id,
                       organization_id, status, attempt_count, last_error, created_at, delivered_at
                FROM outbox_events WHERE organization_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new OutboxStatusView(rs.getObject("id", UUID.class), rs.getString("event_key"),
                rs.getString("event_type"), rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                rs.getObject("actor_customer_id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("status"), rs.getInt("attempt_count"), rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("delivered_at") == null ? null
                        : rs.getTimestamp("delivered_at").toInstant()), organizationId, safeLimit);
    }

    @Transactional
    public int retryDeadLetter(UUID eventId, UUID organizationId, UUID actorCustomerId) {
        List<OutboxRetryState> attempts = jdbcTemplate.query("""
                SELECT attempt_count, organization_id FROM outbox_events
                WHERE id = ? AND status = 'DEAD' FOR UPDATE
                """, (rs, row) -> new OutboxRetryState(rs.getInt("attempt_count"),
                rs.getObject("organization_id", UUID.class)), eventId);
        if (attempts.isEmpty() || !organizationId.equals(attempts.getFirst().organizationId())) {
            throw new IllegalArgumentException("A dead-letter event was not found.");
        }
        int previousAttempts = attempts.getFirst().attemptCount();
        jdbcTemplate.update("""
                UPDATE outbox_events SET status = 'PENDING', attempt_count = 0,
                    next_attempt_at = clock_timestamp(), lease_until = NULL, last_error = NULL
                WHERE id = ? AND status = 'DEAD'
                """, eventId);
        recordAudit(actorCustomerId, "OUTBOX_MANUAL_RETRY", "OUTBOX_EVENT", eventId, organizationId,
                Map.of("previousAttemptCount", previousAttempts));
        return previousAttempts;
    }

    public String correlationId() {
        return MDC.get("correlationId");
    }

    private String json(Map<String, ?> value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        }
        catch (JacksonException exception) {
            throw new IllegalArgumentException("Event payload could not be encoded.", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    public record OutboxStatusView(UUID id, String eventKey, String eventType, String aggregateType,
            UUID aggregateId, UUID actorCustomerId, UUID organizationId, String status, int attemptCount,
            String lastError, Instant createdAt, Instant deliveredAt) {
    }

    private record OutboxRetryState(int attemptCount, UUID organizationId) {
    }
}
