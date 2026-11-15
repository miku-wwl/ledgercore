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
    void clearingEffectAndReserveShortfallAlertsFireOnRealThresholdsAndClear() {
        assertThat(LedgerCoreObservability.evaluateClearingEffectAlerts(1, 1, 61, 3_000))
                .containsExactly("CLEARING_FINANCIAL_EFFECT_PENDING", "CLEARING_RESERVE_SHORTFALL");
        assertThat(LedgerCoreObservability.evaluateClearingEffectAlerts(1, 1, 10, 0)).isEmpty();
        assertThat(LedgerCoreObservability.evaluateClearingEffectAlerts(1, 0, 0, 0)).isEmpty();
        assertThat(LedgerCoreObservability.evaluateClearingEffectAlerts(0, -1, -1, -1)).isEmpty();
    }

    @Test
    void unavailableMonitorDoesNotMisreportDatabaseBacklogOrLockAlerts() {
        assertThat(LedgerCoreObservability.evaluateAlerts(0, -1, -1, -1, 0, -1))
                .containsExactly("MONITORING_DATABASE_UNAVAILABLE");
    }
}
