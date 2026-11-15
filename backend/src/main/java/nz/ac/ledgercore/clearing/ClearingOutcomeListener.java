package nz.ac.ledgercore.clearing;

import java.util.Map;

import nz.ac.ledgercore.api.E6FaultInjectionHook;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Low-latency attempt to apply a clearing outcome right after the callback commit. It is only an optimisation: Spring
 * runs AFTER_COMMIT listeners in afterCompletion and swallows their exceptions, so this listener must never be the only
 * owner of money movement. The durable owner is {@link ClearingCallbackEffectOutboxHandler} (Round 4, R4-01).
 */
@Component
public class ClearingOutcomeListener {

    private final ClearingFinancialEffects effects;
    private final ObjectProvider<E6FaultInjectionHook> faultLab;
    private final boolean fastPathEnabled;

    public ClearingOutcomeListener(ClearingFinancialEffects effects, ObjectProvider<E6FaultInjectionHook> faultLab,
            @Value("${ledgercore.clearing.effect.fast-path-enabled:true}") boolean fastPathEnabled) {
        this.effects = effects;
        this.faultLab = faultLab;
        this.fastPathEnabled = fastPathEnabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void applyFinancialOutcome(ClearingOutcomeRecorded outcome) {
        E6FaultInjectionHook hook = faultLab.getIfAvailable();
        if (hook != null && outcome.terminal()) {
            hook.checkpoint(E6FaultInjectionHook.CLEARING_CALLBACK_COMMITTED_BEFORE_EFFECT, Map.of(
                    "paymentId", outcome.paymentId().toString(), "operationId", outcome.operationId().toString(),
                    "externalStatus", outcome.externalStatus()));
        }
        if (fastPathEnabled) {
            // Also for duplicates and non-terminal callbacks: the processor derives the owed effect from the locked row.
            effects.tryApplyNow(outcome.paymentId());
        }
    }
}
