package nz.ac.ledgercore.ledger;

public enum JournalEntryType {
	OPENING_FUNDING,
	SIMULATED_DEPOSIT,
	TRANSFER,
	REVERSAL,
	CORPORATE_PAYMENT_BOOKING,
	CLEARING_SETTLEMENT,
	CORPORATE_PAYMENT_REFUND,
	/** Round 4 (R4-02): asset-to-asset movement between the simulation reserve and the simulation treasury. */
	RESERVE_LIQUIDITY_TRANSFER
}
