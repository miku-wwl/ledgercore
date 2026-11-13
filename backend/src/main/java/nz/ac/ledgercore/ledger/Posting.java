package nz.ac.ledgercore.ledger;

import java.util.Objects;
import java.util.UUID;

public record Posting(UUID ledgerAccountId, PostingSide side, long amountMinor) {

	public Posting {
		Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");
		Objects.requireNonNull(side, "side");
		if (amountMinor <= 0) {
			throw new IllegalArgumentException("Posting amount must be greater than zero.");
		}
	}
}
