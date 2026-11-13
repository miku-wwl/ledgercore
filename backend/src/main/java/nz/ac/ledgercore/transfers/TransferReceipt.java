package nz.ac.ledgercore.transfers;

import java.time.Instant;
import java.util.UUID;

/** Stable success receipt persisted with the transfer and replayed for the original idempotency key. */
public record TransferReceipt(UUID transferId, UUID sourceAccountId, UUID targetAccountId, String status,
		String amount, String currency, Instant createdAt) {
}
