package nz.ac.ledgercore.operations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import nz.ac.ledgercore.organizations.OrganizationAuthorizationService;
import nz.ac.ledgercore.organizations.OrganizationRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationsConsoleService {

    private final JdbcTemplate jdbc;
    private final OrganizationAuthorizationService authorization;

    public OperationsConsoleService(JdbcTemplate jdbc, OrganizationAuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @Transactional(readOnly = true)
    public OperationsMetrics metrics(UUID actorCustomerId, UUID organizationId) {
        authorization.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
        return jdbc.queryForObject("""
                SELECT
                    (SELECT count(*) FROM corporate_payment_intents
                     WHERE organization_id = ? AND status = 'PENDING_CLEARING') AS pending_clearing,
                    (SELECT count(*) FROM corporate_payment_intents
                     WHERE organization_id = ? AND external_status = 'UNKNOWN') AS unknown_clearing,
                    (SELECT count(*) FROM outbox_events
                     WHERE organization_id = ? AND status IN ('PENDING', 'PROCESSING')) AS pending_events,
                    (SELECT count(*) FROM outbox_events
                     WHERE organization_id = ? AND status = 'DEAD') AS failed_events,
                    (SELECT count(*) FROM reconciliation_exceptions
                     WHERE organization_id = ? AND status IN ('OPEN', 'ASSIGNED')) AS open_exceptions,
                    (SELECT min(created_at) FROM outbox_events
                     WHERE organization_id = ? AND status IN ('PENDING', 'PROCESSING')) AS oldest_pending_event_at
                """, (rs, row) -> new OperationsMetrics(rs.getLong("pending_clearing"),
                rs.getLong("unknown_clearing"), rs.getLong("pending_events"), rs.getLong("failed_events"),
                rs.getLong("open_exceptions"), rs.getTimestamp("oldest_pending_event_at") == null ? null
                        : rs.getTimestamp("oldest_pending_event_at").toInstant()), organizationId, organizationId,
                organizationId, organizationId, organizationId, organizationId);
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> auditHistory(UUID actorCustomerId, UUID organizationId, int limit) {
        authorization.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
        int safeLimit = Math.max(1, Math.min(100, limit));
        return jdbc.query("""
                SELECT id, actor_customer_id, action, resource_type, resource_id, correlation_id, occurred_at
                FROM audit_events WHERE organization_id = ?
                ORDER BY occurred_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new AuditEventView(rs.getObject("id", UUID.class),
                rs.getObject("actor_customer_id", UUID.class), rs.getString("action"),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                rs.getString("correlation_id"), rs.getTimestamp("occurred_at").toInstant()), organizationId,
                safeLimit);
    }

    @Transactional(readOnly = true)
    public List<OperationSearchResult> search(UUID actorCustomerId, UUID organizationId, String query) {
        authorization.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
        String normalized = query.trim();
        String partial = "%" + normalized + "%";
        return jdbc.query("""
                SELECT * FROM (
                    SELECT 'PAYMENT'::varchar AS result_type, p.id::text AS result_id,
                           p.status::varchar AS status, p.external_status::varchar AS external_status,
                           p.amount_minor::bigint AS amount_minor, 'Corporate payment'::varchar AS description,
                           NULL::varchar AS correlation_id, p.updated_at AS occurred_at
                    FROM corporate_payment_intents p
                    WHERE p.organization_id = ? AND (p.id::text = ? OR p.operation_id::text = ?)
                    UNION ALL
                    SELECT 'OUTBOX_EVENT'::varchar, e.id::text, e.status::varchar, NULL::varchar,
                           NULL::bigint, e.event_type::varchar, e.correlation_id::varchar, e.created_at
                    FROM outbox_events e
                    WHERE e.organization_id = ? AND
                          (e.id::text = ? OR e.aggregate_id::text = ? OR e.correlation_id ILIKE ?)
                    UNION ALL
                    SELECT 'AUDIT_EVENT'::varchar, a.id::text, a.action::varchar, NULL::varchar,
                           NULL::bigint, a.resource_type::varchar, a.correlation_id::varchar, a.occurred_at
                    FROM audit_events a
                    WHERE a.organization_id = ? AND
                          (a.id::text = ? OR a.resource_id::text = ? OR a.correlation_id ILIKE ?)
                ) matches
                ORDER BY occurred_at DESC, result_type, result_id
                LIMIT 50
                """, (rs, row) -> new OperationSearchResult(rs.getString("result_type"),
                rs.getString("result_id"), rs.getString("status"), rs.getString("external_status"),
                (Long) rs.getObject("amount_minor"), rs.getString("description"), rs.getString("correlation_id"),
                rs.getTimestamp("occurred_at").toInstant()), organizationId, normalized, normalized, organizationId,
                normalized, normalized, partial, organizationId, normalized, normalized, partial);
    }

    public record OperationsMetrics(long pendingClearing, long unknownClearing, long pendingEvents,
            long failedEvents, long openExceptions, Instant oldestPendingEventAt) {
    }

    /** Deliberately omits audit details JSON, which may contain sensitive business facts. */
    public record AuditEventView(UUID id, UUID actorCustomerId, String action, String resourceType, UUID resourceId,
            String correlationId, Instant occurredAt) {
    }

    public record OperationSearchResult(String resultType, String resultId, String status, String externalStatus,
            Long amountMinor, String description, String correlationId, Instant occurredAt) {
    }
}
