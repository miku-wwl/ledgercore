package nz.ac.ledgercore.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReconciliationRunResult(UUID runId, UUID organizationId, Instant completedAt,
		List<String> checkedDomains, List<String> skippedDomains, int findingCount,
		Map<String, Long> findingCounts, List<ReconciliationFinding> findings) {
}
