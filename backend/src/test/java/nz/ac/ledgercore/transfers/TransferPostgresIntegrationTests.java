package nz.ac.ledgercore.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.ledger.LedgerBalanceReader;
import nz.ac.ledgercore.ledger.LedgerWriter;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.organizations.OrganizationRole;
import nz.ac.ledgercore.organizations.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class TransferPostgresIntegrationTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	AccountService accountService;

	@Autowired
	OrganizationService organizationService;

	@Autowired
	TransferService transferService;

	@Autowired
	LedgerWriter ledgerWriter;

	@Autowired
	LedgerBalanceReader balanceReader;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void transferIsDoubleEntryAtomicAndItsSuccessfulReceiptIsDurable() {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID aliceAccount = createAccount(alice, "Alice everyday");
		UUID bobAccount = createAccount(bob, "Bob everyday");
		Money amount = Money.parsePositiveNzd("120.00");

		TransferReceipt receipt = transferService.transfer(alice, aliceAccount, bobAccount, amount, "transfer-001");

		assertThat(receipt.amount()).isEqualTo("120.00");
		assertThat(receipt.currency()).isEqualTo("NZD");
		assertThat(receipt.status()).isEqualTo("POSTED");
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(aliceAccount).minorUnits()).isEqualTo(88_000L);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(bobAccount).minorUnits()).isEqualTo(112_000L);
		assertThat(balanceReader.projectionMatchesLedger(aliceAccount)).isTrue();
		assertThat(balanceReader.projectionMatchesLedger(bobAccount)).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE id = ?", Integer.class,
				receipt.transferId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM postings WHERE journal_entry_id = "
				+ "(SELECT journal_entry_id FROM transfers WHERE id = ?)", Integer.class, receipt.transferId())).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM idempotency_records
				WHERE owner_customer_id = ? AND operation = 'TRANSFER' AND idempotency_key = 'transfer-001'
				  AND status = 'SUCCEEDED' AND transfer_id = ? AND response_payload->>'status' = 'POSTED'
				  AND response_payload->>'amount' = '120.00'
				""", Integer.class, alice, receipt.transferId())).isEqualTo(1);
		assertThat(transferService.transfer(alice, aliceAccount, bobAccount, amount, "transfer-001"))
				.isEqualTo(receipt);
		assertThatThrownBy(() -> transferService.transfer(alice, aliceAccount, bobAccount,
				Money.parsePositiveNzd("121.00"), "transfer-001"))
				.isInstanceOfSatisfying(TransferException.class,
					exception -> assertThat(exception.code()).isEqualTo(TransferErrorCode.IDEMPOTENCY_KEY_REUSED));
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE transfers SET amount_minor = amount_minor + 1 WHERE id = ?",
				receipt.transferId())).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM idempotency_records WHERE owner_customer_id = ? "
				+ "AND operation = 'TRANSFER' AND idempotency_key = 'transfer-001'", alice))
				.isInstanceOf(DataAccessException.class);
		assertThat(transferCount(alice)).isEqualTo(1);
	}

	@Test
	void postgresRejectsTransferRowsThatDoNotMatchTheirJournalPostings() {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID source = createAccount(alice, "Alice");
		UUID target = createAccount(bob, "Bob");
		UUID sourceLedger = customerLedgerAccountId(source);
		UUID targetLedger = customerLedgerAccountId(target);
		UUID entryId = UUID.randomUUID();
		UUID transferId = UUID.randomUUID();
		TransactionTemplate transactions = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> transactions.execute(status -> {
			jdbcTemplate.update("""
					INSERT INTO journal_entries (id, entry_type, currency, reference)
					VALUES (?, 'TRANSFER', 'NZD', 'mismatched-transfer-test')
					""", entryId);
			jdbcTemplate.update("""
					INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency)
					VALUES (?, ?, ?, 'DEBIT', 500, 'NZD'), (?, ?, ?, 'CREDIT', 500, 'NZD')
					""", UUID.randomUUID(), entryId, sourceLedger, UUID.randomUUID(), entryId, targetLedger);
			jdbcTemplate.update("""
					INSERT INTO transfers (id, owner_customer_id, source_account_id, target_account_id,
						amount_minor, currency, journal_entry_id)
					VALUES (?, ?, ?, ?, 501, 'NZD', ?)
					""", transferId, alice, source, target, entryId);
			return null;
		})).isInstanceOf(RuntimeException.class);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE id = ?", Integer.class,
				transferId)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_entries WHERE id = ?", Integer.class,
				entryId)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM postings WHERE journal_entry_id = ?", Integer.class,
				entryId)).isZero();
	}

	@Test
	void postgresRejectsAnIdempotencyReservationWithoutItsSuccessReceiptAtCommit() {
		UUID owner = createCustomer();
		TransactionTemplate transactions = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> transactions.execute(status -> {
			jdbcTemplate.update("""
					INSERT INTO idempotency_records (owner_customer_id, operation, idempotency_key, request_fingerprint)
					VALUES (?, 'TRANSFER', 'incomplete-receipt', repeat('0', 64))
					""", owner);
			return null;
		})).isInstanceOf(RuntimeException.class);

		assertThat(idempotencyCount(owner)).isZero();
	}

	@Test
	void rejectsUnauthorizedSourceAndInsufficientFundsWithoutPersistingFailureReceipts() {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID aliceAccount = createAccount(alice, "Alice");
		UUID bobAccount = createAccount(bob, "Bob");

		assertThatThrownBy(() -> transferService.transfer(bob, aliceAccount, bobAccount,
				Money.parsePositiveNzd("1.00"), "unauthorized-source"))
				.isInstanceOfSatisfying(TransferException.class,
					exception -> assertThat(exception.code()).isEqualTo(TransferErrorCode.ACCOUNT_NOT_FOUND));
		assertThatThrownBy(() -> transferService.transfer(alice, aliceAccount, bobAccount,
				Money.parsePositiveNzd("1000.01"), "insufficient-funds"))
				.isInstanceOfSatisfying(TransferException.class,
					exception -> assertThat(exception.code()).isEqualTo(TransferErrorCode.INSUFFICIENT_FUNDS));

		assertThat(transferCount(alice)).isZero();
		assertThat(idempotencyCount(alice)).isZero();
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(aliceAccount).minorUnits()).isEqualTo(100_000L);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(bobAccount).minorUnits()).isEqualTo(100_000L);
	}

	@Test
	void receiptRecorderCannotBypassApprovedCorporateOperationBoundary() {
		UUID admin = createCustomer();
		var organization = organizationService.create(admin, "E8 transfer boundary " + UUID.randomUUID());
		UUID maker = createCustomer();
		String makerEmail = jdbcTemplate.queryForObject("SELECT email_normalized FROM customers WHERE id = ?",
				String.class, maker);
		organizationService.addMember(admin, organization.id(), makerEmail,
				List.of(OrganizationRole.PAYMENT_MAKER));
		UUID corporateSource = accountService.openOrganizationAccount(organization.id(), "E8 corporate source");
		UUID personalTarget = createAccount(createCustomer(), "E8 personal target");
		long sourceBefore = balanceReader.ledgerBalanceForCustomerAccount(corporateSource).minorUnits();
		long targetBefore = balanceReader.ledgerBalanceForCustomerAccount(personalTarget).minorUnits();
		int journalsBefore = countRows("journal_entries");
		int postingsBefore = countRows("postings");

		assertThatThrownBy(() -> transferService.transferAndRecord(maker, corporateSource, personalTarget,
				Money.parsePositiveNzd("1.00"), "e8-corporate-recorder-bypass",
				receipt -> { throw new AssertionError("An unauthorized recorder must not run."); }))
				.isInstanceOfSatisfying(TransferException.class,
					exception -> assertThat(exception.code()).isEqualTo(TransferErrorCode.CORPORATE_APPROVAL_REQUIRED));

		assertThat(balanceReader.ledgerBalanceForCustomerAccount(corporateSource).minorUnits()).isEqualTo(sourceBefore);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(personalTarget).minorUnits()).isEqualTo(targetBefore);
		assertThat(transferCount(maker)).isZero();
		assertThat(idempotencyCount(maker)).isZero();
		assertThat(countRows("journal_entries")).isEqualTo(journalsBefore);
		assertThat(countRows("postings")).isEqualTo(postingsBefore);
	}

	@Test
	void successfulReplayRechecksSourceOwnershipBeforeReturningTheReceipt() {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID charlie = createCustomer();
		UUID aliceAccount = createAccount(alice, "Alice");
		UUID bobAccount = createAccount(bob, "Bob");
		UUID charlieAccount = createAccount(charlie, "Charlie");
		TransferReceipt receipt = transferService.transfer(alice, aliceAccount, bobAccount,
				Money.parsePositiveNzd("2.00"), "replay-auth-check");

		assertThatThrownBy(() -> transferService.transfer(alice, charlieAccount, bobAccount,
				Money.parsePositiveNzd("2.00"), "replay-auth-check"))
				.isInstanceOfSatisfying(TransferException.class,
					exception -> assertThat(exception.code()).isEqualTo(TransferErrorCode.ACCOUNT_NOT_FOUND));
		assertThat(transferService.transfer(alice, aliceAccount, bobAccount, Money.parsePositiveNzd("2.00"),
				"replay-auth-check")).isEqualTo(receipt);
		assertThat(transferCount(alice)).isEqualTo(1);
	}

	@Test
	void rejectsClosedRecipientWithoutLeavingAnIdempotencyReservation() {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID aliceAccount = createAccount(alice, "Alice");
		UUID bobAccount = createAccount(bob, "Bob");
		UUID openingEntryId = jdbcTemplate.queryForObject(
				"SELECT id FROM journal_entries WHERE reference = ? AND entry_type = 'OPENING_FUNDING'",
				UUID.class, "account:" + bobAccount);
		ledgerWriter.reverseJournalEntry(openingEntryId);
		accountService.closeAccount(bobAccount);

		assertThatThrownBy(() -> transferService.transfer(alice, aliceAccount, bobAccount,
				Money.parsePositiveNzd("1.00"), "closed-recipient"))
				.isInstanceOfSatisfying(TransferException.class,
					exception -> assertThat(exception.code()).isEqualTo(TransferErrorCode.ACCOUNT_NOT_OPEN));
		assertThat(transferCount(alice)).isZero();
		assertThat(idempotencyCount(alice)).isZero();
	}

	@Test
	void concurrentDifferentKeysCannotOverdrawOneSourceAccount() throws Exception {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID charlie = createCustomer();
		UUID source = createAccount(alice, "Alice");
		UUID bobAccount = createAccount(bob, "Bob");
		UUID charlieAccount = createAccount(charlie, "Charlie");

		List<TransferAttempt> results = race(
				() -> attempt(alice, source, bobAccount, "600.00", "spend-a"),
				() -> attempt(alice, source, charlieAccount, "600.00", "spend-b"));

		assertThat(results).filteredOn(result -> result.error() == null).hasSize(1);
		assertThat(results).filteredOn(result -> result.error() == TransferErrorCode.INSUFFICIENT_FUNDS).hasSize(1);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(source).minorUnits()).isEqualTo(40_000L);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(bobAccount).minorUnits()
				+ balanceReader.ledgerBalanceForCustomerAccount(charlieAccount).minorUnits()).isEqualTo(260_000L);
		assertThat(transferCount(alice)).isEqualTo(1);
		assertThat(idempotencyCount(alice)).isEqualTo(1);
		assertThat(transferJournalCount(alice)).isEqualTo(1);
	}

	@Test
	void concurrentCrossTransfersUseDeterministicAccountLocksWithoutDeadlock() throws Exception {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID aliceAccount = createAccount(alice, "Alice");
		UUID bobAccount = createAccount(bob, "Bob");

		List<TransferAttempt> results = race(
				() -> attempt(alice, aliceAccount, bobAccount, "125.00", "cross-a"),
				() -> attempt(bob, bobAccount, aliceAccount, "125.00", "cross-b"));

		assertThat(results).allSatisfy(result -> assertThat(result.error()).isNull());
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(aliceAccount).minorUnits()).isEqualTo(100_000L);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(bobAccount).minorUnits()).isEqualTo(100_000L);
		assertThat(transferCount(alice) + transferCount(bob)).isEqualTo(2);
		assertThat(transferJournalCount(alice, bob)).isEqualTo(2);
	}

	@Test
	void concurrentSameKeyAndSameIntentReturnOnePersistedReceipt() throws Exception {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID source = createAccount(alice, "Alice");
		UUID target = createAccount(bob, "Bob");

		List<TransferAttempt> results = race(
				() -> attempt(alice, source, target, "50.00", "same-key-same-intent"),
				() -> attempt(alice, source, target, "50.00", "same-key-same-intent"));

		assertThat(results).allSatisfy(result -> assertThat(result.error()).isNull());
		assertThat(results.get(0).receipt()).isEqualTo(results.get(1).receipt());
		assertThat(transferCount(alice)).isEqualTo(1);
		assertThat(idempotencyCount(alice)).isEqualTo(1);
		assertThat(transferJournalCount(alice)).isEqualTo(1);
	}

	@Test
	void concurrentSameKeyWithDifferentIntentHasOneWinnerAndOneConflict() throws Exception {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID charlie = createCustomer();
		UUID source = createAccount(alice, "Alice");
		UUID bobAccount = createAccount(bob, "Bob");
		UUID charlieAccount = createAccount(charlie, "Charlie");

		List<TransferAttempt> results = race(
				() -> attempt(alice, source, bobAccount, "30.00", "same-key-different-intent"),
				() -> attempt(alice, source, charlieAccount, "40.00", "same-key-different-intent"));

		assertThat(results).filteredOn(result -> result.error() == null).hasSize(1);
		assertThat(results).filteredOn(result -> result.error() == TransferErrorCode.IDEMPOTENCY_KEY_REUSED).hasSize(1);
		assertThat(transferCount(alice)).isEqualTo(1);
		assertThat(idempotencyCount(alice)).isEqualTo(1);
		assertThat(transferJournalCount(alice)).isEqualTo(1);
	}

	@Test
	void originalKeyRecoversACommittedTransferWhenTheFirstResponseIsLost() {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID source = createAccount(alice, "Alice");
		UUID target = createAccount(bob, "Bob");
		String originalKey = "response-lost-original-key";

		TransferReceipt firstResponse = transferService.transfer(alice, source, target,
				Money.parsePositiveNzd("7.25"), originalKey);
		// Simulate a committed response that the client never receives, then retry the unchanged intent and key.
		TransferReceipt recoveredResponse = transferService.transfer(alice, source, target,
				Money.parsePositiveNzd("7.25"), originalKey);

		assertThat(recoveredResponse).isEqualTo(firstResponse);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(source).minorUnits()).isEqualTo(99_275L);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(target).minorUnits()).isEqualTo(100_725L);
		assertThat(transferCount(alice)).isEqualTo(1);
		assertThat(transferJournalCount(alice)).isEqualTo(1);
	}

	@Test
	void journalInsertFailureRollsBackIdempotencyReservationAndTransferState() {
		assertFailedTransferLeavesNoState("test_fail_transfer_journal", "journal_entries", """
				IF NEW.entry_type = 'TRANSFER' THEN RAISE EXCEPTION 'forced failure before journal insert'; END IF;
				RETURN NEW;
				""");
	}

	@Test
	void secondPostingFailureRollsBackFirstPostingAndIdempotencyReservation() {
		assertFailedTransferLeavesNoState("test_fail_transfer_posting", "postings", """
				IF NEW.side = 'CREDIT' AND EXISTS (
					SELECT 1 FROM journal_entries WHERE id = NEW.journal_entry_id AND entry_type = 'TRANSFER'
				) THEN RAISE EXCEPTION 'forced failure during posting insert'; END IF;
				RETURN NEW;
				""");
	}

	@Test
	void projectionUpdateFailureRollsBackPostingsAndIdempotencyReservation() {
		assertFailedTransferLeavesNoState("test_fail_transfer_projection", "account_balance_projection", """
				RAISE EXCEPTION 'forced failure during projection update';
				RETURN NEW;
				""");
	}

	@Test
	void receiptPersistenceFailureRollsBackLedgerAndTransferWrites() {
		assertFailedTransferLeavesNoState("test_fail_transfer_receipt", "idempotency_records", """
				IF NEW.status = 'SUCCEEDED' THEN RAISE EXCEPTION 'forced failure before receipt persistence'; END IF;
				RETURN NEW;
				""");
	}

	private void assertFailedTransferLeavesNoState(String triggerName, String tableName, String body) {
		UUID alice = createCustomer();
		UUID bob = createCustomer();
		UUID source = createAccount(alice, "Alice");
		UUID target = createAccount(bob, "Bob");
		long sourceBefore = balanceReader.ledgerBalanceForCustomerAccount(source).minorUnits();
		long targetBefore = balanceReader.ledgerBalanceForCustomerAccount(target).minorUnits();
		int journalCountBefore = countRows("journal_entries");
		int postingCountBefore = countRows("postings");
		installFailureTrigger(triggerName, tableName, body);
		try {
			assertThatThrownBy(() -> transferService.transfer(alice, source, target,
					Money.parsePositiveNzd("5.00"), triggerName))
					.isInstanceOf(DataAccessException.class);
		}
		finally {
			dropFailureTrigger(triggerName, tableName);
		}

		assertThat(balanceReader.ledgerBalanceForCustomerAccount(source).minorUnits()).isEqualTo(sourceBefore);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(target).minorUnits()).isEqualTo(targetBefore);
		assertThat(transferCount(alice)).isZero();
		assertThat(idempotencyCount(alice)).isZero();
		assertThat(countRows("journal_entries")).isEqualTo(journalCountBefore);
		assertThat(countRows("postings")).isEqualTo(postingCountBefore);
	}

	private void installFailureTrigger(String triggerName, String tableName, String body) {
		jdbcTemplate.execute("CREATE FUNCTION " + triggerName + "() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN "
				+ body + " END; $$");
		jdbcTemplate.execute("CREATE TRIGGER " + triggerName + " BEFORE INSERT OR UPDATE ON " + tableName
				+ " FOR EACH ROW EXECUTE FUNCTION " + triggerName + "()");
	}

	private void dropFailureTrigger(String triggerName, String tableName) {
		jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON " + tableName);
		jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + triggerName + "()");
	}

	private List<TransferAttempt> race(TransferAction first, TransferAction second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<TransferAttempt> firstResult = executor.submit(gated(ready, start, first));
			Future<TransferAttempt> secondResult = executor.submit(gated(ready, start, second));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			return List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS));
		}
		finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	private Callable<TransferAttempt> gated(CountDownLatch ready, CountDownLatch start, TransferAction action) {
		return () -> {
			ready.countDown();
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for the concurrent transfer barrier.");
			}
			return action.run();
		};
	}

	private TransferAttempt attempt(UUID owner, UUID source, UUID target, String amount, String key) {
		try {
			return new TransferAttempt(transferService.transfer(owner, source, target, Money.parsePositiveNzd(amount), key),
					null);
		}
		catch (TransferException exception) {
			return new TransferAttempt(null, exception.code());
		}
	}

	private UUID createCustomer() {
		UUID customerId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO customers (id, email_normalized, password_hash, display_name)
				VALUES (?, ?, 'test-only-hash', 'Transfer integration customer')
				""", customerId, "transfer-test-" + customerId + "@example.invalid");
		return customerId;
	}

	private UUID createAccount(UUID owner, String name) {
		return accountService.openAccount(owner, name);
	}

	private UUID customerLedgerAccountId(UUID accountId) {
		return jdbcTemplate.queryForObject(
				"SELECT id FROM ledger_accounts WHERE customer_account_id = ? AND kind = 'CUSTOMER_DEPOSIT'",
				UUID.class, accountId);
	}

	private int transferCount(UUID... owners) {
		if (owners.length == 1) {
			return jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE owner_customer_id = ?",
					Integer.class, owners[0]);
		}
		return jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE owner_customer_id IN (?, ?)",
				Integer.class, owners[0], owners[1]);
	}

	private int idempotencyCount(UUID owner) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM idempotency_records WHERE owner_customer_id = ?",
				Integer.class, owner);
	}

	private int transferJournalCount(UUID... owners) {
		if (owners.length == 1) {
			return jdbcTemplate.queryForObject("""
					SELECT count(*) FROM journal_entries je JOIN transfers t ON t.journal_entry_id = je.id
					WHERE t.owner_customer_id = ? AND je.entry_type = 'TRANSFER'
					""", Integer.class, owners[0]);
		}
		return jdbcTemplate.queryForObject("""
				SELECT count(*) FROM journal_entries je JOIN transfers t ON t.journal_entry_id = je.id
				WHERE t.owner_customer_id IN (?, ?) AND je.entry_type = 'TRANSFER'
				""", Integer.class, owners[0], owners[1]);
	}

	private int countRows(String tableName) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName, Integer.class);
	}
	@FunctionalInterface
	private interface TransferAction {
		TransferAttempt run() throws Exception;
	}

	private record TransferAttempt(TransferReceipt receipt, TransferErrorCode error) {
	}
}
