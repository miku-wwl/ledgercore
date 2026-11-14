package nz.ac.ledgercore.ledger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerWriter {

	public static final UUID SIMULATION_RESERVE_ID = UUID.fromString("00000000-0000-5000-8000-000000000001");
	public static final UUID CLEARING_PAYABLE_ID = UUID.fromString("00000000-0000-5000-8000-000000000002");

	private final JdbcTemplate jdbcTemplate;
	private final LedgerBalanceReader balanceReader;

	public LedgerWriter(JdbcTemplate jdbcTemplate, LedgerBalanceReader balanceReader) {
		this.jdbcTemplate = jdbcTemplate;
		this.balanceReader = balanceReader;
	}

	@Transactional
	public void openCustomerDepositLedger(UUID customerAccountId, Money openingFunding) {
		requirePositive(openingFunding);
		requireOpenCustomerAccount(customerAccountId);
		UUID customerLedgerAccountId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system, currency)
				VALUES (?, ?, 'CUSTOMER_DEPOSIT', 'CREDIT', FALSE, 'NZD')
				""", customerLedgerAccountId, customerAccountId);
		jdbcTemplate.update("""
				INSERT INTO account_balance_projection (ledger_account_id, currency, balance_minor)
				VALUES (?, 'NZD', 0)
				""", customerLedgerAccountId);

		lockLedgerAccounts(List.of(customerLedgerAccountId, SIMULATION_RESERVE_ID));
		JournalEntry entry = JournalEntry.openingFunding(SIMULATION_RESERVE_ID, customerLedgerAccountId,
				openingFunding);
		writeEntryAndRefreshProjections(entry, "account:" + customerAccountId);
	}

	@Transactional
	public UUID recordSimulatedDeposit(UUID customerAccountId, Money amount) {
		requirePositive(amount);
		UUID customerLedgerAccountId = customerLedgerAccountId(customerAccountId);
		lockLedgerAccounts(List.of(customerLedgerAccountId, SIMULATION_RESERVE_ID));
		requireOpenCustomerAccount(customerAccountId);
		JournalEntry entry = JournalEntry.simulatedDeposit(SIMULATION_RESERVE_ID, customerLedgerAccountId, amount);
		writeEntryAndRefreshProjections(entry, "account:" + customerAccountId);
		return entry.id();
	}

	/** Locks both customer ledgers in UUID order so account lifecycle checks cannot race a transfer. */
	@Transactional
	public void lockCustomerDepositLedgers(UUID firstCustomerAccountId, UUID secondCustomerAccountId) {
		lockLedgerAccounts(List.of(customerLedgerAccountId(firstCustomerAccountId),
				customerLedgerAccountId(secondCustomerAccountId)));
	}

	/** Writes a balanced transfer after locking and checking its source ledger account. */
	@Transactional
	public UUID transferBetweenCustomerAccounts(UUID sourceCustomerAccountId, UUID targetCustomerAccountId,
			Money amount, UUID transferId) {
		requirePositive(amount);
		if (sourceCustomerAccountId.equals(targetCustomerAccountId)) {
			throw new IllegalArgumentException("A transfer must use two distinct customer accounts.");
		}
		UUID sourceLedgerAccountId = customerLedgerAccountId(sourceCustomerAccountId);
		UUID targetLedgerAccountId = customerLedgerAccountId(targetCustomerAccountId);
		lockLedgerAccounts(List.of(sourceLedgerAccountId, targetLedgerAccountId));
		Money sourceBalance = balanceReader.ledgerBalanceForLedgerAccount(sourceLedgerAccountId);
		if (sourceBalance.minorUnits() < amount.minorUnits()) {
			throw new InsufficientLedgerBalanceException();
		}
		JournalEntry entry = JournalEntry.transfer(sourceLedgerAccountId, targetLedgerAccountId, amount);
		writeEntryAndRefreshProjections(entry, "transfer:" + transferId);
		return entry.id();
	}

	/**
	 * Books the approved external-payment obligation. Authority and financial details are re-read from the locked,
	 * persisted approved intent; no caller-supplied amount or account can authorize this posting.
	 */
	@Transactional
	public UUID bookApprovedCorporatePayment(UUID paymentIntentId) {
		ApprovedPayment payment = lockApprovedPayment(paymentIntentId);
		requireOpenCustomerAccount(payment.sourceAccountId());
		UUID sourceLedgerAccountId = customerLedgerAccountId(payment.sourceAccountId());
		lockLedgerAccounts(List.of(sourceLedgerAccountId, CLEARING_PAYABLE_ID));
		Money sourceBalance = balanceReader.ledgerBalanceForLedgerAccount(sourceLedgerAccountId);
		if (sourceBalance.minorUnits() < payment.amountMinor()) {
			throw new InsufficientLedgerBalanceException();
		}
		JournalEntry entry = JournalEntry.corporatePaymentBooking(sourceLedgerAccountId, CLEARING_PAYABLE_ID,
				new Money(payment.amountMinor()));
		writeEntryAndRefreshProjections(entry, "corporate-payment:" + paymentIntentId + ":booking");
		int updated = jdbcTemplate.update("""
				UPDATE corporate_payment_intents
				SET status = 'PENDING_CLEARING', booking_journal_entry_id = ?, updated_at = clock_timestamp()
				WHERE id = ? AND status = 'APPROVED' AND booking_journal_entry_id IS NULL
				""", entry.id(), paymentIntentId);
		if (updated != 1) {
			throw new IllegalStateException("Approved corporate payment could not be booked exactly once.");
		}
		return entry.id();
	}

	/** Records a settlement only after the authenticated clearing callback updated the persisted external state. */
	@Transactional
	public UUID settleClearingPayment(UUID paymentIntentId) {
		ClearingPayment payment = lockClearingPayment(paymentIntentId, "SETTLED");
		UUID payable = CLEARING_PAYABLE_ID;
		lockLedgerAccounts(List.of(payable, SIMULATION_RESERVE_ID));
		Money payableBalance = balanceReader.ledgerBalanceForLedgerAccount(payable);
		Money reserveBalance = balanceReader.ledgerBalanceForLedgerAccount(SIMULATION_RESERVE_ID);
		if (payableBalance.minorUnits() < payment.amountMinor()) {
			throw new IllegalStateException("Clearing payable does not cover the settlement amount.");
		}
		if (reserveBalance.minorUnits() < payment.amountMinor()) {
			throw new InsufficientLedgerBalanceException();
		}
		JournalEntry entry = JournalEntry.clearingSettlement(payable, SIMULATION_RESERVE_ID,
				new Money(payment.amountMinor()));
		writeEntryAndRefreshProjections(entry, "corporate-payment:" + paymentIntentId + ":settlement");
		int updated = jdbcTemplate.update("""
				UPDATE corporate_payment_intents
				SET status = 'COMPLETED', settlement_journal_entry_id = ?, completed_at = clock_timestamp(),
				    updated_at = clock_timestamp()
				WHERE id = ? AND status IN ('PENDING_CLEARING', 'UNKNOWN') AND external_status = 'SETTLED'
				  AND settlement_journal_entry_id IS NULL
				""", entry.id(), paymentIntentId);
		if (updated != 1) {
			throw new IllegalStateException("Clearing settlement could not be recorded exactly once.");
		}
		return entry.id();
	}

	/** Restores customer funds only for a definitive rejection received before settlement. */
	@Transactional
	public UUID refundRejectedCorporatePayment(UUID paymentIntentId) {
		ClearingPayment payment = lockClearingPayment(paymentIntentId, "REJECTED");
		UUID deposit = customerLedgerAccountId(payment.sourceAccountId());
		lockLedgerAccounts(List.of(CLEARING_PAYABLE_ID, deposit));
		Money payableBalance = balanceReader.ledgerBalanceForLedgerAccount(CLEARING_PAYABLE_ID);
		if (payableBalance.minorUnits() < payment.amountMinor()) {
			throw new IllegalStateException("Clearing payable does not cover the refund amount.");
		}
		JournalEntry entry = JournalEntry.corporatePaymentRefund(CLEARING_PAYABLE_ID, deposit,
				new Money(payment.amountMinor()));
		writeEntryAndRefreshProjections(entry, "corporate-payment:" + paymentIntentId + ":refund");
		int updated = jdbcTemplate.update("""
				UPDATE corporate_payment_intents
				SET status = 'REJECTED', reversal_journal_entry_id = ?, completed_at = clock_timestamp(),
				    updated_at = clock_timestamp()
				WHERE id = ? AND status IN ('PENDING_CLEARING', 'UNKNOWN') AND external_status = 'REJECTED'
				  AND settlement_journal_entry_id IS NULL AND reversal_journal_entry_id IS NULL
				""", entry.id(), paymentIntentId);
		if (updated != 1) {
			throw new IllegalStateException("Rejected corporate payment could not be refunded exactly once.");
		}
		return entry.id();
	}

	@Transactional
	public UUID reverseJournalEntry(UUID journalEntryId) {
		JournalEntry original = loadJournalEntry(journalEntryId);
		if (original.type() == JournalEntryType.CORPORATE_PAYMENT_BOOKING
				|| original.type() == JournalEntryType.CLEARING_SETTLEMENT
				|| original.type() == JournalEntryType.CORPORATE_PAYMENT_REFUND) {
			throw new IllegalArgumentException("Corporate payment entries require their controlled recovery workflow.");
		}
		JournalEntry reversal = JournalEntry.reversalOf(original);
		List<UUID> ledgerAccountIds = reversal.postings().stream().map(Posting::ledgerAccountId).toList();
		lockLedgerAccounts(ledgerAccountIds);
		Integer existingReversals = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM journal_entries WHERE reverses_entry_id = ?", Integer.class, journalEntryId);
		if (existingReversals != null && existingReversals > 0) {
			throw new IllegalStateException("A journal entry can be reversed only once.");
		}
		writeEntryAndRefreshProjections(reversal, "entry:" + journalEntryId);
		return reversal.id();
	}

	/** Locks the customer's ledger row before account status changes. */
	@Transactional
	public void lockCustomerAccountLedger(UUID customerAccountId) {
		lockLedgerAccounts(List.of(customerLedgerAccountId(customerAccountId)));
	}

	private void writeEntryAndRefreshProjections(JournalEntry entry, String reference) {
		jdbcTemplate.update("""
				INSERT INTO journal_entries (id, entry_type, currency, reference, reverses_entry_id)
				VALUES (?, ?, 'NZD', ?, ?)
				""", entry.id(), entry.type().name(), reference, entry.reversesEntryId());
		for (Posting posting : entry.postings()) {
			jdbcTemplate.update("""
					INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency)
					VALUES (?, ?, ?, ?, ?, 'NZD')
					""", UUID.randomUUID(), entry.id(), posting.ledgerAccountId(), posting.side().name(),
					posting.amountMinor());
		}
		entry.postings().stream().map(Posting::ledgerAccountId).distinct().forEach(this::rebuildProjectionFromLedger);
	}

	private void rebuildProjectionFromLedger(UUID ledgerAccountId) {
		long ledgerBalance = balanceReader.ledgerBalanceForLedgerAccount(ledgerAccountId).minorUnits();
		if (ledgerBalance < 0) {
			throw new IllegalStateException("The journal entry would make a normal ledger balance negative.");
		}
		int updated = jdbcTemplate.update("""
				UPDATE account_balance_projection
				SET balance_minor = ?, updated_at = clock_timestamp()
				WHERE ledger_account_id = ?
				""", ledgerBalance, ledgerAccountId);
		if (updated != 1) {
			throw new IllegalStateException("Missing balance projection for ledger account " + ledgerAccountId);
		}
	}

	private JournalEntry loadJournalEntry(UUID journalEntryId) {
		List<JournalEntry> entries = jdbcTemplate.query("""
				SELECT id, entry_type, reverses_entry_id
				FROM journal_entries
				WHERE id = ?
				""", (resultSet, rowNumber) -> {
			UUID id = resultSet.getObject("id", UUID.class);
			JournalEntryType type = JournalEntryType.valueOf(resultSet.getString("entry_type"));
			UUID reversesId = resultSet.getObject("reverses_entry_id", UUID.class);
			List<Posting> postings = jdbcTemplate.query("""
					SELECT ledger_account_id, side, amount_minor
					FROM postings
					WHERE journal_entry_id = ?
					ORDER BY ledger_account_id
					""", (postingResult, postingRow) -> new Posting(
						postingResult.getObject("ledger_account_id", UUID.class),
						PostingSide.valueOf(postingResult.getString("side")), postingResult.getLong("amount_minor")), id);
			return new JournalEntry(id, type, reversesId, postings);
		}, journalEntryId);
		if (entries.isEmpty()) {
			throw new IllegalArgumentException("Journal entry does not exist: " + journalEntryId);
		}
		return entries.getFirst();
	}

	private UUID customerLedgerAccountId(UUID customerAccountId) {
		List<UUID> ids = jdbcTemplate.query(
				"SELECT id FROM ledger_accounts WHERE customer_account_id = ? AND kind = 'CUSTOMER_DEPOSIT'",
				(resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), customerAccountId);
		if (ids.isEmpty()) {
			throw new IllegalArgumentException("Customer account has no ledger account: " + customerAccountId);
		}
		return ids.getFirst();
	}

	private ApprovedPayment lockApprovedPayment(UUID paymentIntentId) {
		List<ApprovedPayment> rows = jdbcTemplate.query("""
				SELECT source_account_id, amount_minor
				FROM corporate_payment_intents
				WHERE id = ? AND status = 'APPROVED' AND booking_journal_entry_id IS NULL
				  AND approved_by_customer_id IS NOT NULL AND approved_digest = intent_digest
				  AND approved_version = version
				FOR UPDATE
				""", (rs, row) -> new ApprovedPayment(rs.getObject("source_account_id", UUID.class),
					rs.getLong("amount_minor")), paymentIntentId);
		if (rows.isEmpty()) {			throw new IllegalStateException("A persisted independently approved corporate payment is required.");
		}
		return rows.getFirst();
	}

	private ClearingPayment lockClearingPayment(UUID paymentIntentId, String externalStatus) {
		List<ClearingPayment> rows = jdbcTemplate.query("""
				SELECT source_account_id, amount_minor
				FROM corporate_payment_intents
				WHERE id = ? AND status IN ('PENDING_CLEARING', 'UNKNOWN') AND external_status = ?
				FOR UPDATE
				""", (rs, row) -> new ClearingPayment(rs.getObject("source_account_id", UUID.class),
					rs.getLong("amount_minor")), paymentIntentId, externalStatus);
		if (rows.isEmpty()) {
			throw new IllegalStateException("The persisted clearing outcome does not authorize this ledger action.");
		}
		return rows.getFirst();
	}

	private void lockLedgerAccounts(List<UUID> ledgerAccountIds) {
		List<UUID> sortedIds = new ArrayList<>(ledgerAccountIds.stream().distinct().toList());
		sortedIds.sort(Comparator.naturalOrder());
		for (UUID id : sortedIds) {
			List<UUID> rows = jdbcTemplate.query("SELECT id FROM ledger_accounts WHERE id = ? FOR UPDATE",
					(resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), id);
			if (rows.isEmpty()) {
				throw new IllegalArgumentException("Ledger account does not exist: " + id);
			}
		}
	}

	private void requireOpenCustomerAccount(UUID customerAccountId) {
		List<String> statuses = jdbcTemplate.query("SELECT status FROM accounts WHERE id = ?",
				(resultSet, rowNumber) -> resultSet.getString(1), customerAccountId);
		if (statuses.isEmpty()) {
			throw new IllegalArgumentException("Customer account does not exist: " + customerAccountId);
		}
		if (!"OPEN".equals(statuses.getFirst())) {
			throw new IllegalStateException("Only an open customer account can receive simulated funds.");
		}
	}

	private void requirePositive(Money amount) {
		if (amount == null || amount.minorUnits() <= 0) {
			throw new IllegalArgumentException("Amount must be greater than zero.");
		}
	}

	private record ApprovedPayment(UUID sourceAccountId, long amountMinor) {
	}

	private record ClearingPayment(UUID sourceAccountId, long amountMinor) {
	}
}
