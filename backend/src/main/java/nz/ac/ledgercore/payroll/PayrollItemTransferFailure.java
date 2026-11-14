package nz.ac.ledgercore.payroll;

import java.util.UUID;

/**
 * A classified, permanent payroll item failure. The item's transfer transaction has rolled back; the orchestrator
 * records the failure code as the item's terminal result in a separate transaction.
 */
public final class PayrollItemTransferFailure extends RuntimeException {

    private final UUID batchId;
    private final UUID itemId;
    private final String failureCode;

    public PayrollItemTransferFailure(UUID batchId, UUID itemId, String failureCode, String message, Throwable cause) {
        super(message, cause);
        this.batchId = batchId;
        this.itemId = itemId;
        this.failureCode = failureCode;
    }

    public UUID batchId() {
        return batchId;
    }

    public UUID itemId() {
        return itemId;
    }

    public String failureCode() {
        return failureCode;
    }
}
