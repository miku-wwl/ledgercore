package nz.ac.ledgercore.transfers;

/**
 * Synchronous same-transaction hook for recording a completed transfer in a calling module's durable workflow.
 * Implementations must perform database work only; the hook runs before the transfer transaction commits.
 */
@FunctionalInterface
public interface TransferReceiptRecorder {

    void record(TransferReceipt receipt);
}
