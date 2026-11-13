package nz.ac.ledgercore.transactionhistory;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import nz.ac.ledgercore.identity.CustomerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/accounts/{accountId}/transactions")
public class TransactionHistoryController {

	private final TransactionHistoryService transactionHistoryService;

	public TransactionHistoryController(TransactionHistoryService transactionHistoryService) {
		this.transactionHistoryService = transactionHistoryService;
	}

	@GetMapping
	TransactionHistoryService.TransactionPage list(@AuthenticationPrincipal CustomerPrincipal principal,
			@PathVariable UUID accountId, @RequestParam(defaultValue = "20") int limit,
			@RequestParam(required = false) String cursor) {
		return transactionHistoryService.list(principal.customerId(), accountId, limit, cursor);
	}

	@GetMapping("/{transferId}")
	TransactionHistoryService.TransactionDetail detail(@AuthenticationPrincipal CustomerPrincipal principal,
			@PathVariable UUID accountId, @PathVariable UUID transferId) {
		return transactionHistoryService.detail(principal.customerId(), accountId, transferId);
	}
}
