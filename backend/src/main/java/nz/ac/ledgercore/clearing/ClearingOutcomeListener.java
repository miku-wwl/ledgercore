package nz.ac.ledgercore.clearing;

import java.util.Map;

import nz.ac.ledgercore.api.E6FaultInjectionHook;
import nz.ac.ledgercore.ledger.InsufficientLedgerBalanceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ClearingOutcomeListener {

    private final ClearingFinancialEffectProcessor effects;
    private final ClearingExceptionRecorder exceptions;
    private final ObjectProvider<E6FaultInjectionHook> faultLab;

    public ClearingOutcomeListener(ClearingFinancialEffectProcessor effects, ClearingExceptionRecorder exceptions,
            ObjectProvider<E6FaultInjectionHook> faultLab) {
        this.effects = effects;
        this.exceptions = exceptions;
        this.faultLab = faultLab;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void applyFinancialOutcome(ClearingOutcomeRecorded outcome) {
        E6FaultInjectionHook hook = faultLab.getIfAvailable();
        if (hook != null && outcome.terminal()) {
            hook.checkpoint(E6FaultInjectionHook.CLEARING_CALLBACK_COMMITTED_BEFORE_EFFECT, Map.of(
                    "paymentId", outcome.paymentId().toString(), "operationId", outcome.operationId().toString(),
                    "externalStatus", outcome.externalStatus()));
        }
        try {
            effects.apply(outcome.paymentId());
        }
        catch (InsufficientLedgerBalanceException exception) {
            exceptions.recordReserveInsufficient(outcome.paymentId(), outcome.operationId(), outcome.organizationId());
        }
    }
}
