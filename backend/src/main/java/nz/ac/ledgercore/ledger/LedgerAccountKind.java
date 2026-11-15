package nz.ac.ledgercore.ledger;

public enum LedgerAccountKind {
	CUSTOMER_DEPOSIT,
	SIMULATION_RESERVE,
	CLEARING_PAYABLE,
	/** Round 4 (R4-02): simulated bank liquidity held outside the settlement reserve. */
	SIMULATION_TREASURY
}
