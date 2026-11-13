package nz.ac.ledgercore.ledger;

public enum PostingSide {
	DEBIT,
	CREDIT;

	public PostingSide opposite() {
		return this == DEBIT ? CREDIT : DEBIT;
	}
}
