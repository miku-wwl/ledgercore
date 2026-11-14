package nz.ac.ledgercore;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Counts completed Spring transaction rollbacks at a fixed set of financial boundaries. */
@Component
public class TransactionRollbackMetrics {

    private final MeterRegistry registry;

    public TransactionRollbackMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void observe(String boundary) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    registry.counter("ledgercore.transaction.rollbacks", "boundary", boundary).increment();
                }
            }
        });
    }
}
