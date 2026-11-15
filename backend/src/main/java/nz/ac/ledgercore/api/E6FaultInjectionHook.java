package nz.ac.ledgercore.api;

import java.util.Map;

/** Shared seam for the opt-in E6 fault lab; its only implementation is profile-gated and disposable-database-only. */
public interface E6FaultInjectionHook {

	String TRANSFER_PRE_COMMIT = "TRANSFER_PRE_COMMIT";
	String TRANSFER_AFTER_COMMIT_BEFORE_RESPONSE = "TRANSFER_AFTER_COMMIT_BEFORE_RESPONSE";
	String OUTBOX_PERSISTED_BEFORE_DISPATCH = "OUTBOX_PERSISTED_BEFORE_DISPATCH";
	String PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK = "PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK";
	/** Round 4: the terminal clearing callback is committed; its financial effect has not started. */
	String CLEARING_CALLBACK_COMMITTED_BEFORE_EFFECT = "CLEARING_CALLBACK_COMMITTED_BEFORE_EFFECT";
	/** Round 4: the settlement or refund journal is written inside the effect transaction, not yet committed. */
	String CLEARING_EFFECT_POSTED_BEFORE_COMMIT = "CLEARING_EFFECT_POSTED_BEFORE_COMMIT";
	/** Round 4: callback and financial effect are committed; the HTTP acknowledgement is not yet sent. */
	String CLEARING_EFFECT_COMMITTED_BEFORE_ACK = "CLEARING_EFFECT_COMMITTED_BEFORE_ACK";

	void checkpoint(String point, Map<String, ?> facts);
}
