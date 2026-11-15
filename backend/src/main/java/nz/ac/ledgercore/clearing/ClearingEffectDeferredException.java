package nz.ac.ledgercore.clearing;

/** The owed clearing effect is valid but cannot be applied yet; its durable work item must be retried, not dropped. */
public class ClearingEffectDeferredException extends RuntimeException {

    public ClearingEffectDeferredException(String message, Throwable cause) {
        super(message, cause);
    }
}
