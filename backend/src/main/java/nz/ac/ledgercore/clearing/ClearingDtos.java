package nz.ac.ledgercore.clearing;

import java.time.Instant;
import java.util.UUID;

public final class ClearingDtos {

    private ClearingDtos() {
    }

    public record OperationRequest(UUID operationId, UUID paymentId, String intentDigest, long amountMinor,
            String currency, String beneficiaryReference, String routingCode) {
    }

    /** afterSequence: the highest callback sequence the bank holds; later callbacks are redelivered (R4-07). */
    public record InquiryRequest(UUID operationId, Long afterSequence) {
    }

    public record OperationResponse(UUID operationId, UUID paymentId, String intentDigest, String externalStatus,
            String simulatorReference, long sequenceNo) {
    }

    public record Callback(UUID callbackId, UUID operationId, UUID paymentId, String intentDigest,
            long sequenceNo, String externalStatus, String simulatorReference, Instant occurredAt) {
    }

    public record CallbackAck(UUID callbackId, String result) {
    }
}
