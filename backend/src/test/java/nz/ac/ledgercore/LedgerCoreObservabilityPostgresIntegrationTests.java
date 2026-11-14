package nz.ac.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import nz.ac.ledgercore.identity.CustomerPrincipal;
import nz.ac.ledgercore.identity.IdentityService;
import nz.ac.ledgercore.organizations.OrganizationRole;
import nz.ac.ledgercore.organizations.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class LedgerCoreObservabilityPostgresIntegrationTests {

    @Autowired
    LedgerCoreObservability observability;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HikariDataSource applicationDataSource;

    @Autowired
    IdentityService identityService;

    @Autowired
    OrganizationService organizationService;

    @Autowired
    org.springframework.test.web.servlet.MockMvc mockMvc;

    @Test
    void publishesLiveDatabaseAndPoolGaugesWithoutIdentityTags() {
        var snapshot = observability.refreshNow();
        assertThat(snapshot.databaseAvailable()).isEqualTo(1);
        assertThat(snapshot.outboxPending()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.outboxDead()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.unknownClearing()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.openReconciliation()).isGreaterThanOrEqualTo(0);
        assertThat(registry.find("ledgercore.outbox.pending.events").gauge()).isNotNull();
        assertThat(registry.find("ledgercore.postgresql.lock.wait.sessions").gauge()).isNotNull();
        assertThat(registry.find("ledgercore.hikari.pending.acquisitions").gauge()).isNotNull();
        assertThat(registry.find("hikaricp.connections.acquire").timer()).isNotNull();
        assertThat(registry.find("hikaricp.connections.pending").gauge()).isNotNull();
        assertThat(registry).isInstanceOf(PrometheusMeterRegistry.class);
        assertThat(((PrometheusMeterRegistry) registry).scrape()).contains("ledgercore_outbox_pending_events");

        Set<String> tagKeys = registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getKey()).collect(Collectors.toSet());
        assertThat(tagKeys).doesNotContain("customerId", "accountId", "organizationId", "paymentId",
                "payrollBatchId", "correlationId", "idempotencyKey", "beneficiary");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class,
                "ix_payroll_item_results_pending_batch_item")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Integer.class,
                "ix_payroll_item_results_pending")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void actuatorMetricsAndAlertEndpointRequireAnActiveOperationsMembership() throws Exception {
        var admin = identityService.register("e9-observability-admin-" + java.util.UUID.randomUUID() + "@example.test",
                "Observability-Integration-Password!", "E9 Admin");
        var operator = identityService.register("e9-observability-operator-" + java.util.UUID.randomUUID() + "@example.test",
                "Observability-Integration-Password!", "E9 Operations");
        var organization = organizationService.create(admin.id(), "E9 metrics " + java.util.UUID.randomUUID());
        organizationService.addMember(admin.id(), organization.id(), operator.email(),
                java.util.List.of(OrganizationRole.OPERATIONS));
        var principal = new CustomerPrincipal(operator.id(), operator.email(), "", operator.displayName());
        var authentication = new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities());

        mockMvc.perform(get("/actuator/ledgercore").with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseAvailable").value(1));
        mockMvc.perform(get("/actuator/prometheus").with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("ledgercore_outbox_pending_events")));
        mockMvc.perform(get("/actuator/ledgercore"))
                .andExpect(status().isUnauthorized());
        var ordinaryUser = identityService.register("e9-observability-user-" + java.util.UUID.randomUUID()
                + "@example.test", "Observability-Integration-Password!", "E9 Customer");
        var ordinaryPrincipal = new CustomerPrincipal(ordinaryUser.id(), ordinaryUser.email(), "",
                ordinaryUser.displayName());
        var ordinaryAuthentication = new UsernamePasswordAuthenticationToken(ordinaryPrincipal, "",
                ordinaryPrincipal.getAuthorities());
        mockMvc.perform(get("/actuator/prometheus").with(authentication(ordinaryAuthentication)))
                .andExpect(status().isForbidden());
    }

    @Test
    void separatelyDetectsAndClearsApplicationPoolWait() throws Exception {
        int maximumPoolSize = applicationDataSource.getMaximumPoolSize();
        List<Connection> held = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (int index = 0; index < maximumPoolSize; index++) {
                held.add(applicationDataSource.getConnection());
            }
            CountDownLatch started = new CountDownLatch(1);
            var waitingConnection = executor.submit(() -> {
                started.countDown();
                return applicationDataSource.getConnection();
            });
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            awaitCondition(() -> applicationDataSource.getHikariPoolMXBean().getThreadsAwaitingConnection() > 0);
            var saturated = observability.refreshNow();
            assertThat(saturated.hikariPendingAcquisitions()).isGreaterThan(0);
            assertThat(saturated.alerts()).contains("HIKARI_CONNECTION_WAIT");

            held.removeFirst().close();
            try (Connection acquired = waitingConnection.get(5, TimeUnit.SECONDS)) {
                assertThat(acquired.isClosed()).isFalse();
            }
            var cleared = observability.refreshNow();
            assertThat(cleared.hikariPendingAcquisitions()).isZero();
            assertThat(cleared.alerts()).doesNotContain("HIKARI_CONNECTION_WAIT");
        }
        finally {
            for (Connection connection : held) {
                connection.close();
            }
            executor.shutdownNow();
        }
    }

    @Test
    void separatelyDetectsAndClearsPostgresRowLockWait() throws Exception {
        var customer = identityService.register("e9-lock-" + java.util.UUID.randomUUID() + "@example.test",
                "Observability-Integration-Password!", "E9 Lock Fixture");
        try (Connection locker = applicationDataSource.getConnection()) {
            locker.setAutoCommit(false);
            try (PreparedStatement lock = locker.prepareStatement("SELECT id FROM customers WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, customer.id());
                lock.executeQuery().close();
            }
            CountDownLatch started = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var blockedUpdate = executor.submit(() -> {
                    started.countDown();
                    try (Connection waiting = DriverManager.getConnection(applicationDataSource.getJdbcUrl(),
                            applicationDataSource.getUsername(), applicationDataSource.getPassword());
                            PreparedStatement update = waiting.prepareStatement(
                                    "UPDATE customers SET display_name = display_name WHERE id = ?")) {
                        update.setObject(1, customer.id());
                        return update.executeUpdate();
                    }
                });
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                awaitCondition(() -> observability.refreshNow().postgresqlLockWaitSessions() > 0);
                var locked = observability.snapshot();
                assertThat(locked.postgresqlLockWaitSessions()).isGreaterThan(0);
                assertThat(locked.alerts()).contains("POSTGRESQL_LOCK_WAIT");

                locker.commit();
                assertThat(blockedUpdate.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                var cleared = observability.refreshNow();
                assertThat(cleared.postgresqlLockWaitSessions()).isZero();
                assertThat(cleared.alerts()).doesNotContain("POSTGRESQL_LOCK_WAIT");
            }
            finally {
                executor.shutdownNow();
                if (!locker.getAutoCommit()) {
                    locker.rollback();
                }
            }
        }
    }

    private void awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
