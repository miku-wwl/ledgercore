package nz.ac.ledgercore.api;

import java.util.Map;

/** Shared seam for the opt-in E6 fault lab; its only implementation is profile-gated and disposable-database-only. */
public interface E6FaultInjectionHook {

	String TRANSFER_PRE_COMMIT = "TRANSFER_PRE_COMMIT";
	String TRANSFER_AFTER_COMMIT_BEFORE_RESPONSE = "TRANSFER_AFTER_COMMIT_BEFORE_RESPONSE";
	String OUTBOX_PERSISTED_BEFORE_DISPATCH = "OUTBOX_PERSISTED_BEFORE_DISPATCH";
	String PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK = "PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK";

	void checkpoint(String point, Map<String, ?> facts);
}
