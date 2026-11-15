package nz.ac.ledgercore.clearing;

import java.util.UUID;

public record ClearingOutcomeRecorded(UUID paymentId, UUID operationId, UUID organizationId, String externalStatus) {

    /** A terminal external outcome is the only kind that can require a local settlement or refund journal. */
    public boolean terminal() {
        return "SETTLED".equals(externalStatus) || "REJECTED".equals(externalStatus);
    }
}
