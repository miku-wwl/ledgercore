package nz.ac.ledgercore.accounts;

import java.util.UUID;

public record AccountOwner(UUID customerId, UUID organizationId) {

    public AccountOwner {
        if ((customerId == null) == (organizationId == null)) {
            throw new IllegalArgumentException("An account must have exactly one owner.");
        }
    }
}
