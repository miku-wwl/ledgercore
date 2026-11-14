package nz.ac.ledgercore.clearing;

import nz.ac.ledgercore.ledger.InsufficientLedgerBalanceException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ClearingOutcomeListener {

    private final ClearingFinancialEffectProcessor effects;
    private final ClearingExceptionRecorder exceptions;

    public ClearingOutcomeListener(ClearingFinancialEffectProcessor effects, ClearingExceptionRecorder exceptions) {
        this.effects = effects;
        this.exceptions = exceptions;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void applyFinancialOutcome(ClearingOutcomeRecorded outcome) {
        try {
            effects.apply(outcome.paymentId());
        }
        catch (InsufficientLedgerBalanceException exception) {
            exceptions.recordReserveInsufficient(outcome.paymentId(), outcome.operationId(), outcome.organizationId());
        }
    }
}
