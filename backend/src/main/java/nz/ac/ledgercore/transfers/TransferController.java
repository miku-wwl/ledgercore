package nz.ac.ledgercore.transfers;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import nz.ac.ledgercore.identity.CustomerPrincipal;
import nz.ac.ledgercore.ledger.Money;

@Validated
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

	private final TransferService transferService;

	public TransferController(TransferService transferService) {
		this.transferService = transferService;
	}

	@PostMapping
	ResponseEntity<TransferReceipt> transfer(@AuthenticationPrincipal CustomerPrincipal principal,
			@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody TransferRequest request) {
		Money amount = Money.parsePositiveNzd(request.amount());
		TransferReceipt receipt = transferService.transfer(principal.customerId(), request.sourceAccountId(),
				request.targetAccountId(), amount, idempotencyKey);
		return ResponseEntity.ok(receipt);
	}

	public record TransferRequest(@NotNull UUID sourceAccountId, @NotNull UUID targetAccountId,
			@NotBlank @Size(max = 32) String amount) {
	}
}
