package nz.ac.ledgercore.transfers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.ledger.InsufficientLedgerBalanceException;
import nz.ac.ledgercore.ledger.LedgerWriter;
import nz.ac.ledgercore.ledger.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

	private static final String OPERATION = "TRANSFER";
	private static final Pattern VALID_IDEMPOTENCY_KEY = Pattern.compile("[!-~]{1,128}");

	private final JdbcTemplate jdbcTemplate;
	private final AccountService accountService;
	private final LedgerWriter ledgerWriter;

	public TransferService(JdbcTemplate jdbcTemplate, AccountService accountService, LedgerWriter ledgerWriter) {
		this.jdbcTemplate = jdbcTemplate;
		this.accountService = accountService;
		this.ledgerWriter = ledgerWriter;
	}

	/**
	 * Executes one transfer and its durable success receipt in a single READ COMMITTED transaction.
	 * The caller identity must come from the authenticated server-side principal.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public TransferReceipt transfer(UUID authenticatedCustomerId, UUID sourceAccountId, UUID targetAccountId,
			Money amount, String idempotencyKey) {
		validateRequest(authenticatedCustomerId, sourceAccountId, targetAccountId, amount, idempotencyKey);
		if (!accountService.belongsTo(authenticatedCustomerId, sourceAccountId)) {
			throw error(TransferErrorCode.ACCOUNT_NOT_FOUND, "The source account was not found.");
		}
		if (sourceAccountId.equals(targetAccountId)) {
			throw error(TransferErrorCode.INVALID_REQUEST, "A transfer requires two different accounts.");
		}

		String fingerprint = fingerprint(authenticatedCustomerId, sourceAccountId, targetAccountId, amount);
		int reserved = jdbcTemplate.update("""
				INSERT INTO idempotency_records
					(owner_customer_id, operation, idempotency_key, request_fingerprint)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (owner_customer_id, operation, idempotency_key) DO NOTHING
				""", authenticatedCustomerId, OPERATION, idempotencyKey, fingerprint);
		if (reserved == 0) {
			return replayReceipt(authenticatedCustomerId, idempotencyKey, fingerprint);
		}

		lockAndValidateAccounts(sourceAccountId, targetAccountId);
		UUID transferId = UUID.randomUUID();
		UUID journalEntryId;
		try {
			journalEntryId = ledgerWriter.transferBetweenCustomerAccounts(sourceAccountId, targetAccountId, amount,
					transferId);
		}
		catch (InsufficientLedgerBalanceException exception) {
			throw error(TransferErrorCode.INSUFFICIENT_FUNDS,
					"The source account does not have enough available funds.");
		}
		catch (IllegalArgumentException exception) {
			throw error(TransferErrorCode.ACCOUNT_NOT_FOUND, "One of the accounts was not found.");
		}

		jdbcTemplate.update("""
				INSERT INTO transfers
					(id, owner_customer_id, source_account_id, target_account_id, amount_minor, currency, journal_entry_id)
				VALUES (?, ?, ?, ?, ?, 'NZD', ?)
				""", transferId, authenticatedCustomerId, sourceAccountId, targetAccountId, amount.minorUnits(),
				journalEntryId);
		Timestamp createdAt = jdbcTemplate.queryForObject("SELECT created_at FROM transfers WHERE id = ?",
				Timestamp.class, transferId);
		TransferReceipt receipt = new TransferReceipt(transferId, sourceAccountId, targetAccountId, "POSTED",
				amount.toNzdString(), "NZD", Objects.requireNonNull(createdAt, "createdAt").toInstant());
		int completed = jdbcTemplate.update("""
				UPDATE idempotency_records
				SET status = 'SUCCEEDED', transfer_id = ?, response_payload = jsonb_build_object(
					'transferId', ?, 'sourceAccountId', ?, 'targetAccountId', ?, 'status', ?, 'amount', ?, 'currency', ?,
					'createdAt', ?::timestamptz)
				WHERE owner_customer_id = ? AND operation = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
			""", transferId, receipt.transferId(), receipt.sourceAccountId(), receipt.targetAccountId(), receipt.status(),
				receipt.amount(), receipt.currency(), receipt.createdAt().toString(), authenticatedCustomerId, OPERATION,
				idempotencyKey);
		if (completed != 1) {
			throw new IllegalStateException("The transfer success receipt could not be finalized.");
		}
		return receipt;
	}

	private void validateRequest(UUID authenticatedCustomerId, UUID sourceAccountId, UUID targetAccountId,
			Money amount, String idempotencyKey) {
		if (authenticatedCustomerId == null) {
			throw error(TransferErrorCode.UNAUTHENTICATED, "An authenticated customer is required.");
		}
		if (sourceAccountId == null || targetAccountId == null || amount == null || amount.minorUnits() <= 0) {
			throw error(TransferErrorCode.INVALID_REQUEST, "Account identifiers and a positive NZD amount are required.");
		}
		if (idempotencyKey == null || !VALID_IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
			throw error(TransferErrorCode.INVALID_REQUEST,
					"Idempotency-Key must contain 1 to 128 visible ASCII characters.");
		}
	}

	private void lockAndValidateAccounts(UUID sourceAccountId, UUID targetAccountId) {
		try {
			ledgerWriter.lockCustomerDepositLedgers(sourceAccountId, targetAccountId);
		}
		catch (IllegalArgumentException exception) {
			throw error(TransferErrorCode.ACCOUNT_NOT_FOUND, "One of the accounts was not found.");
		}
		assertOpen(sourceAccountId);
		assertOpen(targetAccountId);
	}

	private void assertOpen(UUID customerAccountId) {
		try {
			accountService.assertOpen(customerAccountId);
		}
		catch (IllegalArgumentException exception) {
			throw error(TransferErrorCode.ACCOUNT_NOT_FOUND, "One of the accounts was not found.");
		}
		catch (IllegalStateException exception) {
			throw error(TransferErrorCode.ACCOUNT_NOT_OPEN, "Both accounts must be open to transfer funds.");
		}
	}

	private TransferReceipt replayReceipt(UUID ownerCustomerId, String idempotencyKey, String fingerprint) {
		List<StoredIdempotencyRecord> records = jdbcTemplate.query("""
				SELECT request_fingerprint, status, response_payload->>'transferId',
				       response_payload->>'sourceAccountId', response_payload->>'targetAccountId',
				       response_payload->>'status', response_payload->>'amount', response_payload->>'currency',
				       (response_payload->>'createdAt')::timestamptz
				FROM idempotency_records
				WHERE owner_customer_id = ? AND operation = ? AND idempotency_key = ?
				""", (resultSet, rowNumber) -> {
			String transferId = resultSet.getString(3);
			TransferReceipt receipt = transferId == null ? null : new TransferReceipt(UUID.fromString(transferId),
					UUID.fromString(resultSet.getString(4)), UUID.fromString(resultSet.getString(5)), resultSet.getString(6),
					resultSet.getString(7), resultSet.getString(8),
					Objects.requireNonNull(resultSet.getTimestamp(9), "createdAt").toInstant());
			return new StoredIdempotencyRecord(resultSet.getString(1), resultSet.getString(2), receipt);
		}, ownerCustomerId, OPERATION, idempotencyKey);
		if (records.isEmpty()) {
			throw new IllegalStateException("The conflicting idempotency record is not visible after key reservation.");
		}
		StoredIdempotencyRecord record = records.getFirst();
		if (!MessageDigest.isEqual(record.fingerprint().getBytes(StandardCharsets.US_ASCII),
				fingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw error(TransferErrorCode.IDEMPOTENCY_KEY_REUSED,
					"This Idempotency-Key is already bound to a different transfer intent.");
		}
		if (!"SUCCEEDED".equals(record.status()) || record.receipt() == null) {
			throw new IllegalStateException("A committed idempotency record has no successful receipt.");
		}
		return record.receipt();
	}

	private String fingerprint(UUID ownerCustomerId, UUID sourceAccountId, UUID targetAccountId, Money amount) {
		String canonical = String.join("\n", OPERATION, ownerCustomerId.toString(), sourceAccountId.toString(),
				targetAccountId.toString(), "NZD", Long.toString(amount.minorUnits()));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}

	private TransferException error(TransferErrorCode code, String message) {
		return new TransferException(code, message);
	}

	private record StoredIdempotencyRecord(String fingerprint, String status, TransferReceipt receipt) {
	}
}
