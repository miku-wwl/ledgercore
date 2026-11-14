package nz.ac.ledgercore.clearing;

import java.util.UUID;

public record ClearingOutcomeRecorded(UUID paymentId, UUID operationId, UUID organizationId) {
}
