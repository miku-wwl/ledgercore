package nz.ac.ledgercore.reconciliation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nz.ac.ledgercore.api.ApiNotFoundException;
import nz.ac.ledgercore.events.DurableEventStore;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationService;
import nz.ac.ledgercore.organizations.OrganizationRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReconciliationExceptionService {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcTemplate jdbc;
	private final OrganizationAuthorizationService authorization;
	private final DurableEventStore events;

	public ReconciliationExceptionService(JdbcTemplate jdbc, OrganizationAuthorizationService authorization,
			DurableEventStore events) {
		this.jdbc = jdbc;
		this.authorization = authorization;
		this.events = events;
	}

	@Transactional
	public ReconciliationRunResult recordRun(UUID actorCustomerId, UUID organizationId,
			List<String> checkedDomains, List<String> skippedDomains, List<ReconciliationFinding> rawFindings) {
		UUID runId = UUID.randomUUID();
		// Serialize issue upserts for one organization so two overlapping scans do not create duplicate open cases.
		jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 7142))", rs -> { },
				organizationId.toString());
		List<ReconciliationFinding> findings = new ArrayList<>(rawFindings.size());
		for (ReconciliationFinding finding : rawFindings) {
			findings.add(finding.withExceptionId(upsertFinding(organizationId, finding)));
		}
		Map<String, Long> counts = new java.util.TreeMap<>();
		for (ReconciliationFinding finding : findings) {
			counts.merge(finding.category(), 1L, Long::sum);
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("runId", runId.toString());
		details.put("checkedDomains", checkedDomains);
		details.put("skippedDomains", skippedDomains);
		details.put("findingCount", findings.size());
		details.put("findingCounts", counts);
		details.put("findings", findings.stream().map(finding -> Map.of("category", finding.category(),
				"severity", finding.severity(), "resourceType", finding.resourceType(),
				"resourceId", finding.resourceId().toString(), "expectedFacts", finding.expectedFacts(),
				"observedFacts", finding.observedFacts(), "exceptionId", finding.exceptionId().toString())).toList());
		events.recordAudit(actorCustomerId, "RECONCILIATION_RUN_COMPLETED", "RECONCILIATION_RUN", runId,
				organizationId, details);
		return new ReconciliationRunResult(runId, organizationId, java.time.Instant.now(),
				List.copyOf(checkedDomains), List.copyOf(skippedDomains), findings.size(), Map.copyOf(counts),
				List.copyOf(findings));
	}

	@Transactional(readOnly = true)
	public List<ReconciliationExceptionView> list(UUID actorCustomerId, UUID organizationId,
			String status, int limit) {
		authorization.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
		String selectedStatus = status == null ? null : status.toUpperCase(java.util.Locale.ROOT);
		if (selectedStatus != null && !List.of("OPEN", "ASSIGNED", "RESOLVED").contains(selectedStatus)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exception status is invalid.");
		}
		int safeLimit = Math.max(1, Math.min(200, limit));
		return jdbc.query("""
				SELECT id, category, severity, operation_id, organization_id, expected_facts::text AS expected_facts_json,
				       observed_facts::text AS observed_facts_json, status, assigned_to_customer_id,
				       resolved_by_customer_id, resolution, correction_journal_entry_id, detected_at, resolved_at
				FROM reconciliation_exceptions
				WHERE organization_id = ? AND (CAST(? AS varchar) IS NULL OR status = ?)
				ORDER BY CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END,
			         detected_at DESC, id DESC
				LIMIT ?
				""", (rs, row) -> new ReconciliationExceptionView(rs.getObject("id", UUID.class),
				rs.getString("category"), rs.getString("severity"), rs.getObject("operation_id", UUID.class),
				rs.getObject("organization_id", UUID.class), rs.getString("expected_facts_json"),
				rs.getString("observed_facts_json"), rs.getString("status"),
				rs.getObject("assigned_to_customer_id", UUID.class),
				rs.getObject("resolved_by_customer_id", UUID.class), rs.getString("resolution"),
				rs.getObject("correction_journal_entry_id", UUID.class), rs.getTimestamp("detected_at").toInstant(),
				rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant()),
				organizationId, selectedStatus, selectedStatus, safeLimit);
	}

	@Transactional
	public ReconciliationExceptionView assign(UUID actorCustomerId, UUID organizationId, UUID exceptionId,
			UUID assignedToCustomerId) {
		authorization.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
		authorization.requireRole(assignedToCustomerId, organizationId, OrganizationRole.OPERATIONS);
		ReconciliationExceptionView exception = lockException(organizationId, exceptionId);
		if ("RESOLVED".equals(exception.status())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A resolved reconciliation exception is immutable.");
		}
		if (!assignedToCustomerId.equals(exception.assignedToCustomerId())
				|| !"ASSIGNED".equals(exception.status())) {
			jdbc.update("""
					UPDATE reconciliation_exceptions
					SET assigned_to_customer_id = ?, status = 'ASSIGNED'
					WHERE id = ? AND organization_id = ? AND status IN ('OPEN', 'ASSIGNED')
					""", assignedToCustomerId, exceptionId, organizationId);
			events.recordAudit(actorCustomerId, "RECONCILIATION_EXCEPTION_ASSIGNED", "RECONCILIATION_EXCEPTION",
					exceptionId, organizationId, Map.of("assignedToCustomerId", assignedToCustomerId.toString()));
		}
		return lockException(organizationId, exceptionId);
	}

	@Transactional
	public ReconciliationExceptionView resolve(UUID actorCustomerId, UUID organizationId, UUID exceptionId,
			String resolution, UUID correctionJournalEntryId) {
		authorization.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
		String note = resolution == null ? "" : resolution.trim();
		if (note.isEmpty() || note.length() > 500) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resolution must contain 1 to 500 characters.");
		}
		ReconciliationExceptionView exception = lockException(organizationId, exceptionId);
		if ("RESOLVED".equals(exception.status())) {
			if (actorCustomerId.equals(exception.resolvedByCustomerId()) && note.equals(exception.resolution())
					&& java.util.Objects.equals(correctionJournalEntryId, exception.correctionJournalEntryId())) {
				return exception;
			}
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A reconciliation exception is already resolved.");
		}
		if (correctionJournalEntryId != null) {
			validateCorrection(correctionJournalEntryId);
		}
		int updated = jdbc.update("""
				UPDATE reconciliation_exceptions
				SET status = 'RESOLVED', resolved_by_customer_id = ?, resolved_at = clock_timestamp(),
			    resolution = ?, correction_journal_entry_id = ?
			WHERE id = ? AND organization_id = ? AND status IN ('OPEN', 'ASSIGNED')
			""", actorCustomerId, note, correctionJournalEntryId, exceptionId, organizationId);
		if (updated != 1) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "The reconciliation exception changed before resolution.");
		}
		events.recordAudit(actorCustomerId, "RECONCILIATION_EXCEPTION_RESOLVED", "RECONCILIATION_EXCEPTION",
				exceptionId, organizationId, Map.of("resolution", note,
						"correctionJournalEntryId", correctionJournalEntryId == null ? "NONE"
								: correctionJournalEntryId.toString()));
		return lockException(organizationId, exceptionId);
	}

	private UUID upsertFinding(UUID organizationId, ReconciliationFinding finding) {
		List<UUID> existing = jdbc.query("""
				SELECT id FROM reconciliation_exceptions
				WHERE organization_id = ? AND category = ? AND status <> 'RESOLVED'
				  AND expected_facts ->> 'resourceType' = ? AND expected_facts ->> 'resourceId' = ?
				ORDER BY detected_at DESC, id DESC LIMIT 1 FOR UPDATE
				""", (rs, row) -> rs.getObject(1, UUID.class), organizationId, finding.category(),
				finding.resourceType(), finding.resourceId().toString());
		try {
			if (!existing.isEmpty()) {
				// Keep the original exception facts immutable. Later run-specific observations remain in its audit row.
				return existing.getFirst();
			}
			String expected = JSON.writeValueAsString(finding.expectedFacts());
			String observed = JSON.writeValueAsString(finding.observedFacts());
			UUID id = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO reconciliation_exceptions (id, category, severity, operation_id, organization_id,
					    expected_facts, observed_facts)
					VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
					""", id, finding.category(), finding.severity(), finding.operationId(), organizationId,
					expected, observed);
			return id;
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Reconciliation evidence could not be serialized.", exception);
		}
	}

	private void validateCorrection(UUID correctionJournalEntryId) {
		Integer valid = jdbc.queryForObject("""
				SELECT count(*) FROM (
				    SELECT je.id FROM journal_entries je
				    JOIN postings p ON p.journal_entry_id = je.id
				    WHERE je.id = ? AND je.entry_type = 'REVERSAL' AND je.reverses_entry_id IS NOT NULL
				    GROUP BY je.id
				    HAVING count(p.id) >= 2
				       AND sum(p.amount_minor) FILTER (WHERE p.side = 'DEBIT')
				           = sum(p.amount_minor) FILTER (WHERE p.side = 'CREDIT')
				) balanced_reversal
				""", Integer.class, correctionJournalEntryId);
		Integer alreadyLinked = jdbc.queryForObject("""
				SELECT count(*) FROM reconciliation_exceptions
				WHERE correction_journal_entry_id = ? AND status = 'RESOLVED'
				""", Integer.class, correctionJournalEntryId);
		if (valid == null || valid != 1 || alreadyLinked == null || alreadyLinked != 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"A correction must be a new, balanced reversal journal entry not linked to another exception.");
		}
	}

	private ReconciliationExceptionView lockException(UUID organizationId, UUID exceptionId) {
		List<ReconciliationExceptionView> rows = jdbc.query("""
				SELECT id, category, severity, operation_id, organization_id, expected_facts::text AS expected_facts_json,
				       observed_facts::text AS observed_facts_json, status, assigned_to_customer_id,
				       resolved_by_customer_id, resolution, correction_journal_entry_id, detected_at, resolved_at
				FROM reconciliation_exceptions
				WHERE id = ? AND organization_id = ? FOR UPDATE
				""", (rs, row) -> new ReconciliationExceptionView(rs.getObject("id", UUID.class),
				rs.getString("category"), rs.getString("severity"), rs.getObject("operation_id", UUID.class),
				rs.getObject("organization_id", UUID.class), rs.getString("expected_facts_json"),
				rs.getString("observed_facts_json"), rs.getString("status"),
				rs.getObject("assigned_to_customer_id", UUID.class), rs.getObject("resolved_by_customer_id", UUID.class),
				rs.getString("resolution"), rs.getObject("correction_journal_entry_id", UUID.class),
				rs.getTimestamp("detected_at").toInstant(),
				rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant()),
				exceptionId, organizationId);
		if (rows.isEmpty()) {
			throw new ApiNotFoundException("Reconciliation exception was not found.");
		}
		return rows.getFirst();
	}
}
