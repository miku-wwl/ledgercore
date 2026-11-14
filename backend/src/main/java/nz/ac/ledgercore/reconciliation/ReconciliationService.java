package nz.ac.ledgercore.reconciliation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import nz.ac.ledgercore.clearing.ClearingOperationsService;
import nz.ac.ledgercore.clearing.ClearingDtos.OperationResponse;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationService;
import nz.ac.ledgercore.organizations.OrganizationRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import nz.ac.ledgercore.api.ApiNotFoundException;
import nz.ac.ledgercore.ledger.LedgerBalanceReader;
import nz.ac.ledgercore.ledger.Money;

@Service
public class ReconciliationService {

	private final JdbcTemplate jdbcTemplate;
	private final LedgerBalanceReader ledgerBalanceReader;
	private final ReconciliationQueryService queryService;
	private final OrganizationAuthorizationService authorization;
	private final ReconciliationExceptionService exceptionService;
	private final ObjectProvider<ClearingOperationsService> clearingOperations;

	public ReconciliationService(JdbcTemplate jdbcTemplate, LedgerBalanceReader ledgerBalanceReader,
			ReconciliationQueryService queryService, OrganizationAuthorizationService authorization,
			ReconciliationExceptionService exceptionService,
			ObjectProvider<ClearingOperationsService> clearingOperations) {
		this.jdbcTemplate = jdbcTemplate;
		this.ledgerBalanceReader = ledgerBalanceReader;
		this.queryService = queryService;
		this.authorization = authorization;
		this.exceptionService = exceptionService;
		this.clearingOperations = clearingOperations;
	}

	@Transactional(readOnly = true)
	public ReconciliationResult reconcile(UUID ownerCustomerId, UUID accountId) {
		List<UUID> accounts = jdbcTemplate.query("SELECT id FROM accounts WHERE owner_customer_id = ? AND id = ?",
				(rs, row) -> rs.getObject(1, UUID.class), ownerCustomerId, accountId);
		if (accounts.isEmpty()) {
			throw new ApiNotFoundException("Account was not found.");
		}
		Money ledger = ledgerBalanceReader.ledgerBalanceForCustomerAccount(accountId);
		Money projection = ledgerBalanceReader.projectionBalanceForCustomerAccount(accountId);
		return new ReconciliationResult(accountId, ledger.toNzdString(), projection.toNzdString(), ledger.equals(projection));
	}

	public ReconciliationRunResult reconcileOrganization(UUID actorCustomerId, UUID organizationId, int requestedLimit) {
		authorization.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
		int limit = Math.max(1, Math.min(500, requestedLimit));
		List<ReconciliationFinding> findings = new ArrayList<>(queryService.inspect(organizationId));
		List<String> checkedDomains = new ArrayList<>(List.of("LEDGER_PROJECTION", "TRANSFER_JOURNAL_RECEIPT",
				"PAYROLL_RESULTS_AND_TERMINAL_STATES", "CORPORATE_PAYMENT_JOURNALS", "DURABLE_EVENT_COVERAGE",
				"ORGANIZATION_OWNERSHIP"));
		List<String> skippedDomains = new ArrayList<>();
		long clearingCount = queryService.countClearingOperations(organizationId);
		ClearingOperationsService clearing = clearingOperations.getIfAvailable();
		if (clearing == null) {
			skippedDomains.add("CLEARING_SIMULATOR_NOT_CONFIGURED");
		}
		else {
			checkedDomains.add("INDEPENDENT_CLEARING_SIMULATOR_INQUIRY");
			if (clearingCount > limit) {
				skippedDomains.add("CLEARING_SIMULATOR_LIMIT_REACHED:" + (clearingCount - limit));
			}
			for (ReconciliationQueryService.ClearingOperationSnapshot operation
					: queryService.clearingOperations(organizationId, limit)) {
				if ("READY".equals(operation.instructionStatus()) || "DEAD".equals(operation.instructionStatus())) {
					continue;
				}
				try {
					OperationResponse external = clearing.inquire(actorCustomerId, operation.operationId());
					findings.addAll(compareClearing(operation, external));
				}
				catch (RestClientException exception) {
					findings.add(clearingFinding("CLEARING_SIMULATOR_INQUIRY_FAILED", "HIGH", operation,
							facts("expected", "authenticated independent inquiry succeeds"),
							facts("exceptionType", exception.getClass().getSimpleName())));
				}
			}
		}
		return exceptionService.recordRun(actorCustomerId, organizationId, checkedDomains, skippedDomains, findings);
	}

	private List<ReconciliationFinding> compareClearing(
			ReconciliationQueryService.ClearingOperationSnapshot operation, OperationResponse external) {
		List<ReconciliationFinding> findings = new ArrayList<>();
		boolean identityMatches = Objects.equals(operation.operationId(), external.operationId())
				&& Objects.equals(operation.paymentId(), external.paymentId())
				&& Objects.equals(operation.intentDigest(), external.intentDigest());
		if (!identityMatches) {
			findings.add(clearingFinding("CLEARING_SIMULATOR_IDENTITY_MISMATCH", "CRITICAL", operation,
					facts("paymentId", operation.paymentId().toString(), "intentDigest", operation.intentDigest()),
					facts("paymentId", external.paymentId() == null ? null : external.paymentId().toString(),
							"intentDigest", external.intentDigest(), "operationId",
							external.operationId() == null ? null : external.operationId().toString())));
		}
		long localSequence = operation.callbackSequence() == null ? 0 : operation.callbackSequence();
		if (external.sequenceNo() > localSequence) {
			findings.add(clearingFinding("CLEARING_CALLBACK_MISSING", "HIGH", operation,
					facts("simulatorSequence", external.sequenceNo(), "simulatorStatus", external.externalStatus()),
					facts("latestBankCallbackSequence", localSequence, "bankExternalStatus", operation.externalStatus(),
							"bankInstructionStatus", operation.instructionStatus())));
		}
		else if (localSequence > external.sequenceNo()) {
			findings.add(clearingFinding("CLEARING_SEQUENCE_MISMATCH", "HIGH", operation,
					facts("simulatorSequence", external.sequenceNo()),
					facts("latestBankCallbackSequence", localSequence,
							"bankCallbackStatus", operation.callbackStatus())));
		}
		else if (localSequence > 0 && (!Objects.equals(operation.callbackStatus(), external.externalStatus())
				|| !Objects.equals(operation.externalStatus(), external.externalStatus())
				|| !Objects.equals(operation.instructionStatus(), external.externalStatus()))) {
			findings.add(clearingFinding("CLEARING_BANK_SIMULATOR_STATE_MISMATCH", "CRITICAL", operation,
					facts("externalStatus", external.externalStatus(), "sequenceNo", external.sequenceNo()),
					facts("callbackStatus", operation.callbackStatus(), "paymentExternalStatus", operation.externalStatus(),
							"instructionStatus", operation.instructionStatus())));
		}
		if (operation.simulatorReference() != null && external.simulatorReference() != null
				&& !operation.simulatorReference().equals(external.simulatorReference())) {
			findings.add(clearingFinding("CLEARING_SIMULATOR_REFERENCE_MISMATCH", "HIGH", operation,
					facts("simulatorReference", operation.simulatorReference()),
					facts("inquiryReference", external.simulatorReference())));
		}
		return findings;
	}

	private ReconciliationFinding clearingFinding(String category, String severity,
			ReconciliationQueryService.ClearingOperationSnapshot operation, Map<String, Object> expected,
			Map<String, Object> observed) {
		Map<String, Object> expectedFacts = new LinkedHashMap<>(expected);
		expectedFacts.put("resourceType", "CLEARING_INSTRUCTION");
		expectedFacts.put("resourceId", operation.operationId().toString());
		expectedFacts.put("recommendedAction", "Inquire the same operation with the authenticated simulator; "
				+ "do not treat UNKNOWN as rejection.");
		return new ReconciliationFinding(category, severity, "CLEARING_INSTRUCTION", operation.operationId(),
				operation.operationId(), Map.copyOf(expectedFacts), Map.copyOf(observed), null);
	}

	private static Map<String, Object> facts(Object... values) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int index = 0; index < values.length; index += 2) {
			if (values[index + 1] != null) {
				result.put((String) values[index], values[index + 1]);
			}
		}
		return result;
	}

	public record ReconciliationResult(UUID accountId, String authoritativeLedgerBalance,
			String projectedBalance, boolean matches) {
	}
}
