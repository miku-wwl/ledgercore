package nz.ac.ledgercore.accounts.domain;

import java.util.Objects;
import java.util.UUID;

import nz.ac.ledgercore.accounts.AccountStatus;

public record Account(UUID id, UUID ownerCustomerId, UUID ownerOrganizationId, String displayName,
        AccountStatus status, long version) {

    public Account {
        Objects.requireNonNull(id, "id");
        if ((ownerCustomerId == null) == (ownerOrganizationId == null)) {
            throw new IllegalArgumentException("An account must have exactly one owner.");
        }
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(status, "status");
        if (displayName.isBlank() || displayName.length() > 80) {
            throw new IllegalArgumentException("Account name must contain 1 to 80 characters.");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Account version must not be negative.");
        }
    }

    public Account(UUID id, UUID ownerCustomerId, String displayName, AccountStatus status, long version) {
        this(id, ownerCustomerId, null, displayName, status, version);
    }

    public static Account open(UUID id, UUID ownerCustomerId, String displayName) {
        String normalizedName = Objects.requireNonNull(displayName, "displayName").trim();
        return new Account(id, ownerCustomerId, null, normalizedName, AccountStatus.OPEN, 0);
    }

    public static Account openForOrganization(UUID id, UUID ownerOrganizationId, String displayName) {
        String normalizedName = Objects.requireNonNull(displayName, "displayName").trim();
        return new Account(id, null, ownerOrganizationId, normalizedName, AccountStatus.OPEN, 0);
    }

    public Account suspend() {
        if (status != AccountStatus.OPEN) {
            throw new IllegalStateException("Only an open account can be suspended.");
        }
        return withStatus(AccountStatus.SUSPENDED);
    }

    public Account reactivate() {
        if (status != AccountStatus.SUSPENDED) {
            throw new IllegalStateException("Only a suspended account can be reactivated.");
        }
        return withStatus(AccountStatus.OPEN);
    }

    public Account close(long ledgerBalanceMinor) {
        if (status == AccountStatus.CLOSED) {
            throw new IllegalStateException("A closed account cannot be changed.");
        }
        if (ledgerBalanceMinor != 0) {
            throw new IllegalStateException("An account can be closed only when its ledger balance is zero.");
        }
        return withStatus(AccountStatus.CLOSED);
    }

    private Account withStatus(AccountStatus nextStatus) {
        return new Account(id, ownerCustomerId, ownerOrganizationId, displayName, nextStatus,
                Math.addExact(version, 1));
    }
}
