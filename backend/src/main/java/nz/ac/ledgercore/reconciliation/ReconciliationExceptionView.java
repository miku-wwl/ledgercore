package nz.ac.ledgercore.reconciliation;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationExceptionView(UUID id, String category, String severity, UUID operationId,
		UUID organizationId, String expectedFactsJson, String observedFactsJson, String status,
		UUID assignedToCustomerId, UUID resolvedByCustomerId, String resolution,
		UUID correctionJournalEntryId, Instant detectedAt, Instant resolvedAt) {
}
