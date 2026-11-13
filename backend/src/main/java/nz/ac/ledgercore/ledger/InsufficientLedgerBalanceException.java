package nz.ac.ledgercore.ledger;

public final class InsufficientLedgerBalanceException extends RuntimeException {

	public InsufficientLedgerBalanceException() {
		super("The source ledger account does not have enough available funds.");
	}
}
