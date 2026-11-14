package nz.ac.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LedgerCoreObservabilityAlertTests {

    @Test
    void alertsFireOnRegisteredThresholdsAndClearAfterResolution() {
        assertThat(LedgerCoreObservability.evaluateAlerts(1, 31, 1, 1, 1, 1))
                .containsExactly("OUTBOX_PENDING_OVER_30_SECONDS", "CLEARING_OUTCOME_UNKNOWN",
                        "RECONCILIATION_EXCEPTION_OPEN", "POSTGRESQL_LOCK_WAIT", "HIKARI_CONNECTION_WAIT");
        assertThat(LedgerCoreObservability.evaluateAlerts(1, 0, 0, 0, 0, 0)).isEmpty();
    }

    @Test
    void unavailableMonitorDoesNotMisreportDatabaseBacklogOrLockAlerts() {
        assertThat(LedgerCoreObservability.evaluateAlerts(0, -1, -1, -1, 0, -1))
                .containsExactly("MONITORING_DATABASE_UNAVAILABLE");
    }
}
