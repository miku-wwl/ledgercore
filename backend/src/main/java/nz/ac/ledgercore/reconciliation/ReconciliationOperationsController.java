package nz.ac.ledgercore.reconciliation;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nz.ac.ledgercore.identity.CustomerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/organizations/{organizationId}/operations/reconciliation")
public class ReconciliationOperationsController {

	private final ReconciliationService reconciliationService;
	private final ReconciliationExceptionService exceptionService;

	public ReconciliationOperationsController(ReconciliationService reconciliationService,
			ReconciliationExceptionService exceptionService) {
		this.reconciliationService = reconciliationService;
		this.exceptionService = exceptionService;
	}

	@Operation(operationId = "runOrganizationFinancialReconciliation")
	@PostMapping("/runs")
	ReconciliationRunResult run(@AuthenticationPrincipal CustomerPrincipal principal,
			@PathVariable UUID organizationId, @RequestParam(defaultValue = "500") @Min(1) @Max(500) int limit) {
		return reconciliationService.reconcileOrganization(principal.customerId(), organizationId, limit);
	}

	@Operation(operationId = "listOrganizationReconciliationExceptions")
	@GetMapping("/exceptions")
	List<ReconciliationExceptionView> list(@AuthenticationPrincipal CustomerPrincipal principal,
			@PathVariable UUID organizationId, @RequestParam(required = false) String status,
			@RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
		return exceptionService.list(principal.customerId(), organizationId, status, limit);
	}

	@Operation(operationId = "assignOrganizationReconciliationException")
	@PostMapping("/exceptions/{exceptionId}/assign")
	ReconciliationExceptionView assign(@AuthenticationPrincipal CustomerPrincipal principal,
			@PathVariable UUID organizationId, @PathVariable UUID exceptionId,
			@Valid @RequestBody AssignExceptionRequest request) {
		return exceptionService.assign(principal.customerId(), organizationId, exceptionId,
				request.assignedToCustomerId());
	}

	@Operation(operationId = "resolveOrganizationReconciliationException")
	@PostMapping("/exceptions/{exceptionId}/resolve")
	ReconciliationExceptionView resolve(@AuthenticationPrincipal CustomerPrincipal principal,
			@PathVariable UUID organizationId, @PathVariable UUID exceptionId,
			@Valid @RequestBody ResolveExceptionRequest request) {
		return reconciliationService.resolveException(principal.customerId(), organizationId, exceptionId,
				request.resolution(), request.correctionJournalEntryId());
	}

	public record AssignExceptionRequest(@NotNull UUID assignedToCustomerId) {
	}

	public record ResolveExceptionRequest(@NotBlank @Size(max = 500) String resolution,
			UUID correctionJournalEntryId) {
	}
}
