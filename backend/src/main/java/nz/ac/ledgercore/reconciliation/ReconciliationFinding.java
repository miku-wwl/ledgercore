package nz.ac.ledgercore.reconciliation;

import java.util.Map;
import java.util.UUID;

public record ReconciliationFinding(String category, String severity, String resourceType, UUID resourceId,
		UUID operationId, Map<String, Object> expectedFacts, Map<String, Object> observedFacts, UUID exceptionId) {

	ReconciliationFinding withExceptionId(UUID id) {
		return new ReconciliationFinding(category, severity, resourceType, resourceId, operationId, expectedFacts,
				observedFacts, id);
	}
}
