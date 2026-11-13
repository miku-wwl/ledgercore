package nz.ac.ledgercore.ledger;

import java.util.Objects;
import java.util.UUID;

public record LedgerAccount(UUID id, UUID customerAccountId, LedgerAccountKind kind, PostingSide normalSide,
		boolean systemAccount) {

	public LedgerAccount {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(normalSide, "normalSide");
		if (kind == LedgerAccountKind.CUSTOMER_DEPOSIT
				&& (customerAccountId == null || normalSide != PostingSide.CREDIT || systemAccount)) {
			throw new IllegalArgumentException("Customer deposits are customer-owned credit-normal liabilities.");
		}
		if (kind == LedgerAccountKind.SIMULATION_RESERVE
				&& (customerAccountId != null || normalSide != PostingSide.DEBIT || !systemAccount)) {
			throw new IllegalArgumentException("The simulation reserve is a debit-normal system asset.");
		}
	}
}
