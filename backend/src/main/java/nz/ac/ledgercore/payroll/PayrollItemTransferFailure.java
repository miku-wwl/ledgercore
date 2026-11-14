package nz.ac.ledgercore.payroll;

import java.util.UUID;

import nz.ac.ledgercore.transfers.TransferErrorCode;

public final class PayrollItemTransferFailure extends RuntimeException {

    private final UUID batchId;
    private final UUID itemId;
    private final TransferErrorCode transferCode;

    public PayrollItemTransferFailure(UUID batchId, UUID itemId, TransferErrorCode transferCode, String message,
            Throwable cause) {
        super(message, cause);
        this.batchId = batchId;
        this.itemId = itemId;
        this.transferCode = transferCode;
    }

    public UUID batchId() {
        return batchId;
    }

    public UUID itemId() {
        return itemId;
    }

    public TransferErrorCode transferCode() {
        return transferCode;
    }
}
