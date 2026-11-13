package nz.ac.ledgercore.reconciliation;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import nz.ac.ledgercore.identity.CustomerPrincipal;

@RestController
@RequestMapping("/api/accounts/{accountId}/reconciliation")
public class ReconciliationController {

	private final ReconciliationService reconciliationService;

	public ReconciliationController(ReconciliationService reconciliationService) {
		this.reconciliationService = reconciliationService;
	}

	@GetMapping
	ReconciliationService.ReconciliationResult reconcile(@AuthenticationPrincipal CustomerPrincipal principal,
			@PathVariable UUID accountId) {
		return reconciliationService.reconcile(principal.customerId(), accountId);
	}
}
