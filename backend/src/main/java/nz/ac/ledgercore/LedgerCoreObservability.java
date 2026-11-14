package nz.ac.ledgercore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Low-cardinality operational gauges, read through a connection independent of the application pool. */
@Component
@Endpoint(id = "ledgercore")
public class LedgerCoreObservability {

    private static final Logger log = LoggerFactory.getLogger(LedgerCoreObservability.class);
    private static final String GAUGE_SQL = """
            SELECT
                (SELECT count(*) FROM outbox_events WHERE status IN ('PENDING', 'PROCESSING')),
                (SELECT count(*) FROM outbox_events WHERE status = 'DEAD'),
                COALESCE((SELECT extract(epoch FROM clock_timestamp() - min(created_at))
                          FROM outbox_events WHERE status = 'PENDING'), 0),
                (SELECT count(*) FROM corporate_payment_intents WHERE external_status = 'UNKNOWN'),
                (SELECT count(*) FROM reconciliation_exceptions WHERE status IN ('OPEN', 'ASSIGNED')),
                (SELECT count(*) FROM pg_stat_activity
                 WHERE datname = current_database() AND wait_event_type = 'Lock' AND pid <> pg_backend_pid())
            """;
    private static final String OPERATIONS_MEMBERSHIP_SQL = """
            SELECT EXISTS (
                SELECT 1 FROM organization_memberships m
                JOIN organization_membership_roles r
                  ON r.organization_id = m.organization_id AND r.customer_id = m.customer_id
                JOIN organizations o ON o.id = m.organization_id
                WHERE m.customer_id = ? AND m.status = 'ACTIVE'
                  AND o.status = 'ACTIVE' AND r.role = 'OPERATIONS'
            )
            """;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final HikariDataSource applicationDataSource;
    private final AtomicLong databaseAvailable = new AtomicLong();
    private final AtomicLong outboxPending = new AtomicLong(-1);
    private final AtomicLong outboxDead = new AtomicLong(-1);
    private final AtomicLong oldestPendingSeconds = new AtomicLong(-1);
    private final AtomicLong unknownClearing = new AtomicLong(-1);
    private final AtomicLong openReconciliation = new AtomicLong(-1);
    private final AtomicLong lockWaitSessions = new AtomicLong(-1);
    private final AtomicLong poolPending = new AtomicLong(-1);

    public LedgerCoreObservability(MeterRegistry registry, HikariDataSource applicationDataSource,
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password:}") String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.applicationDataSource = applicationDataSource;
        gauge(registry, "ledgercore.monitoring.database.available", databaseAvailable);
        gauge(registry, "ledgercore.outbox.pending.events", outboxPending);
        gauge(registry, "ledgercore.outbox.dead.events", outboxDead);
        gauge(registry, "ledgercore.outbox.oldest.pending.age.seconds", oldestPendingSeconds);
        gauge(registry, "ledgercore.clearing.unknown.operations", unknownClearing);
        gauge(registry, "ledgercore.reconciliation.open.exceptions", openReconciliation);
        gauge(registry, "ledgercore.postgresql.lock.wait.sessions", lockWaitSessions);
        gauge(registry, "ledgercore.hikari.pending.acquisitions", poolPending);
    }

    @Scheduled(fixedDelayString = "${ledgercore.observability.refresh-ms:5000}",
            initialDelayString = "${ledgercore.observability.refresh-ms:5000}")
    public void refreshScheduled() {
        refreshNow();
    }

    @ReadOperation
    public Snapshot refreshNow() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(GAUGE_SQL)) {
                statement.setQueryTimeout(3);
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    outboxPending.set(row.getLong(1));
                    outboxDead.set(row.getLong(2));
                    oldestPendingSeconds.set(Math.round(row.getDouble(3)));
                    unknownClearing.set(row.getLong(4));
                    openReconciliation.set(row.getLong(5));
                    lockWaitSessions.set(row.getLong(6));
                    databaseAvailable.set(1);
                }
            }
        }
        catch (SQLException exception) {
            databaseAvailable.set(0);
            outboxPending.set(-1);
            outboxDead.set(-1);
            oldestPendingSeconds.set(-1);
            unknownClearing.set(-1);
            openReconciliation.set(-1);
            lockWaitSessions.set(-1);
            log.warn("Operational database gauges are unavailable (SQL state {}).", exception.getSQLState());
        }
        var pool = applicationDataSource.getHikariPoolMXBean();
        poolPending.set(pool == null ? -1 : pool.getThreadsAwaitingConnection());
        return snapshot();
    }

    public Snapshot snapshot() {
        return new Snapshot(databaseAvailable.get(), outboxPending.get(), outboxDead.get(),
                oldestPendingSeconds.get(), unknownClearing.get(), openReconciliation.get(),
                poolPending.get(), lockWaitSessions.get(), evaluateAlerts(databaseAvailable.get(),
                        oldestPendingSeconds.get(), unknownClearing.get(), openReconciliation.get(),
                        poolPending.get(), lockWaitSessions.get()));
    }

    public boolean isOperationsMember(java.util.UUID customerId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(OPERATIONS_MEMBERSHIP_SQL)) {
                statement.setObject(1, customerId);
                statement.setQueryTimeout(3);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() && row.getBoolean(1);
                }
            }
        }
        catch (SQLException exception) {
            log.warn("Operations observability authorization could not be checked (SQL state {}).",
                    exception.getSQLState());
            return false;
        }
    }

    public static List<String> evaluateAlerts(long databaseAvailable, long oldestPendingSeconds,
            long unknownClearing, long openReconciliation, long poolPending, long lockWaitSessions) {
        List<String> alerts = new ArrayList<>();
        if (databaseAvailable == 0) {
            alerts.add("MONITORING_DATABASE_UNAVAILABLE");
        }
        else if (databaseAvailable > 0) {
            if (oldestPendingSeconds > 30) {
                alerts.add("OUTBOX_PENDING_OVER_30_SECONDS");
            }
            if (unknownClearing > 0) {
                alerts.add("CLEARING_OUTCOME_UNKNOWN");
            }
            if (openReconciliation > 0) {
                alerts.add("RECONCILIATION_EXCEPTION_OPEN");
            }
            if (lockWaitSessions > 0) {
                alerts.add("POSTGRESQL_LOCK_WAIT");
            }
        }
        if (poolPending > 0) {
            alerts.add("HIKARI_CONNECTION_WAIT");
        }
        return List.copyOf(alerts);
    }

    private void gauge(MeterRegistry registry, String name, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get).register(registry);
    }

    public record Snapshot(long databaseAvailable, long outboxPending, long outboxDead,
            long oldestPendingAgeSeconds, long unknownClearing, long openReconciliation,
            long hikariPendingAcquisitions, long postgresqlLockWaitSessions, List<String> alerts) {
    }
}
