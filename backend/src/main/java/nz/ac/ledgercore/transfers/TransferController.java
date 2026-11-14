package nz.ac.ledgercore.transfers;

import java.util.UUID;

import nz.ac.ledgercore.api.E6FaultInjectionHook;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
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

	private static final Logger logger = LoggerFactory.getLogger(TransferController.class);

	private final TransferService transferService;
	private final ObjectProvider<E6FaultInjectionHook> e6FaultLab;

	public TransferController(TransferService transferService, ObjectProvider<E6FaultInjectionHook> e6FaultLab) {
		this.transferService = transferService;
		this.e6FaultLab = e6FaultLab;
	}

	@PostMapping
	ResponseEntity<TransferReceipt> transfer(@AuthenticationPrincipal CustomerPrincipal principal,
			@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody TransferRequest request) {
		Money amount = Money.parsePositiveNzd(request.amount());
		long startedAt = System.nanoTime();
		try {
			TransferReceipt receipt = transferService.transfer(principal.customerId(), request.sourceAccountId(),
					request.targetAccountId(), amount, idempotencyKey);
			E6FaultInjectionHook hook = e6FaultLab.getIfAvailable();
			if (hook != null) {
				java.util.Map<String, ?> facts = java.util.Map.of("transferId", receipt.transferId().toString(),
						"idempotencyKey", idempotencyKey);
				hook.checkpoint(E6FaultInjectionHook.TRANSFER_AFTER_COMMIT_BEFORE_RESPONSE, facts);
				hook.checkpoint(E6FaultInjectionHook.OUTBOX_PERSISTED_BEFORE_DISPATCH, facts);
			}
			logTransfer("succeeded", "NONE", startedAt);
			return ResponseEntity.ok(receipt);
		}
		catch (TransferException exception) {
			String outcome = exception.code() == TransferErrorCode.ACCOUNT_NOT_OPEN
					|| exception.code() == TransferErrorCode.IDEMPOTENCY_KEY_REUSED ? "conflict" : "rejected";
			logTransfer(outcome, exception.code().name(), startedAt);
			throw exception;
		}
		catch (RuntimeException exception) {
			logTransfer("internal_error", "UNEXPECTED", startedAt);
			throw exception;
		}
	}

	private void logTransfer(String outcome, String errorCode, long startedAt) {
		LoggingEventBuilder event;
		if ("internal_error".equals(outcome)) {
			event = logger.atError();
		}
		else if ("conflict".equals(outcome) || "rejected".equals(outcome)) {
			event = logger.atWarn();
		}
		else {
			event = logger.atInfo();
		}
		event
			.addKeyValue("event.name", "ledger.transfer")
			.addKeyValue("outcome", outcome)
			.addKeyValue("error.code", errorCode)
			.addKeyValue("duration.ms", (System.nanoTime() - startedAt) / 1_000_000.0);
		event.log("Transfer attempt completed");
	}

	public record TransferRequest(@NotNull UUID sourceAccountId, @NotNull UUID targetAccountId,
			@NotBlank @Size(max = 32) String amount) {
	}
}
