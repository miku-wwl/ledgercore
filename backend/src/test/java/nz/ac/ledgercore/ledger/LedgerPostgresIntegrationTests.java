package nz.ac.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.accounts.AccountStatus;

@ActiveProfiles("test")
@SpringBootTest
class LedgerPostgresIntegrationTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	AccountService accountService;

	@Autowired
	LedgerWriter ledgerWriter;

	@Autowired
	LedgerBalanceReader balanceReader;

	@Autowired
	PlatformTransactionManager transactionManager;

	private UUID ownerCustomerId;

	@BeforeEach
	void createOwner() {
		ownerCustomerId = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO customers (id, email_normalized, password_hash, display_name)
				VALUES (?, ?, 'test-only-hash', 'Ledger test owner')
				""", ownerCustomerId, "ledger-test-" + ownerCustomerId + "@example.invalid");
	}

	@Test
	void openingFundingPersistsTwoBalancedPostingsAndARebuildableProjection() {
		UUID accountId = accountService.openAccount(ownerCustomerId, "Everyday");

		assertThat(accountService.statusOf(accountId)).isEqualTo(AccountStatus.OPEN);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(accountId)).isEqualTo(AccountService.INITIAL_FUNDING);
		assertThat(balanceReader.projectionBalanceForCustomerAccount(accountId)).isEqualTo(AccountService.INITIAL_FUNDING);
		assertThat(balanceReader.projectionMatchesLedger(accountId)).isTrue();
		assertThat(journalCount(accountId)).isEqualTo(1);
		assertThat(postingCount(accountId)).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("""
				SELECT count(*) FROM journal_entries je
				JOIN postings debit ON debit.journal_entry_id = je.id AND debit.side = 'DEBIT'
				JOIN postings credit ON credit.journal_entry_id = je.id AND credit.side = 'CREDIT'
				WHERE je.reference = ? AND debit.amount_minor = credit.amount_minor
				""", Integer.class, "account:" + accountId)).isEqualTo(1);
	}

	@Test
	void simulatedDepositUpdatesLedgerAndProjectionInTheSameTransaction() {
		UUID accountId = accountService.openAccount(ownerCustomerId, "Savings");
		accountService.simulateDeposit(accountId, Money.parsePositiveNzd("25.50"));

		assertThat(balanceReader.ledgerBalanceForCustomerAccount(accountId).minorUnits()).isEqualTo(102_550L);
		assertThat(balanceReader.projectionBalanceForCustomerAccount(accountId).minorUnits()).isEqualTo(102_550L);
		assertThat(balanceReader.projectionMatchesLedger(accountId)).isTrue();
		assertThat(journalCount(accountId)).isEqualTo(2);
		assertThat(postingCount(accountId)).isEqualTo(4);
	}

	@Test
	void rejectsZeroNegativeAndUnknownAccountFunding() {
		UUID accountId = accountService.openAccount(ownerCustomerId, "No overdraft");
		assertThatThrownBy(() -> accountService.simulateDeposit(accountId, new Money(0)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> accountService.simulateDeposit(accountId, new Money(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> accountService.simulateDeposit(UUID.randomUUID(), Money.parsePositiveNzd("1.00")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(journalCount(accountId)).isEqualTo(1);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(accountId)).isEqualTo(AccountService.INITIAL_FUNDING);
	}

	@Test
	void preventsDuplicateOpeningFundingForTheSameCustomerAccount() {
		UUID accountId = accountService.openAccount(ownerCustomerId, "Single funding");

		assertThatThrownBy(() -> ledgerWriter.openCustomerDepositLedger(accountId, AccountService.INITIAL_FUNDING))
				.isInstanceOf(DataAccessException.class);
		assertThat(journalCount(accountId)).isEqualTo(1);
		assertThat(postingCount(accountId)).isEqualTo(2);
	}

	@Test
	void postgresRejectsAnUnbalancedJournalAtCommitAndRollsBackItsRows() {
		UUID accountId = accountService.openAccount(ownerCustomerId, "Balanced only");
		UUID ledgerAccountId = customerLedgerAccountId(accountId);
		UUID invalidEntryId = UUID.randomUUID();
		TransactionTemplate transactions = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> transactions.execute(status -> {
			jdbcTemplate.update("""
					INSERT INTO journal_entries (id, entry_type, currency, reference)
					VALUES (?, 'SIMULATED_DEPOSIT', 'NZD', 'invalid-test-entry')
					""", invalidEntryId);
			jdbcTemplate.update("""
					INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency)
					VALUES (?, ?, ?, 'CREDIT', 100, 'NZD')
					""", UUID.randomUUID(), invalidEntryId, ledgerAccountId);
			return null;
		})).isInstanceOf(RuntimeException.class);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_entries WHERE id = ?", Integer.class,
				invalidEntryId)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM postings WHERE journal_entry_id = ?", Integer.class,
				invalidEntryId)).isZero();
	}

	@Test
	void databaseRejectsMutationAndDeletionOfPostedHistory() {
		UUID accountId = accountService.openAccount(ownerCustomerId, "Append only");
		UUID postingId = jdbcTemplate.queryForObject("""
				SELECT p.id FROM postings p JOIN journal_entries je ON je.id = p.journal_entry_id
				WHERE je.reference = ? ORDER BY p.id LIMIT 1
				""", UUID.class, "account:" + accountId);

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE postings SET amount_minor = amount_minor + 1 WHERE id = ?",
				postingId)).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM postings WHERE id = ?", postingId))
				.isInstanceOf(DataAccessException.class);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM postings WHERE id = ?", Integer.class, postingId))
				.isEqualTo(1);
	}

	@Test
	void reversalsAppendOppositePostingsAndAllowAZeroBalanceAccountToClose() {
		UUID accountId = accountService.openAccount(ownerCustomerId, "Reversible funding");
		UUID openingEntryId = jdbcTemplate.queryForObject(
				"SELECT id FROM journal_entries WHERE reference = ? AND entry_type = 'OPENING_FUNDING'",
				UUID.class, "account:" + accountId);
		UUID reversalId = ledgerWriter.reverseJournalEntry(openingEntryId);

		assertThat(reversalId).isNotEqualTo(openingEntryId);
		assertThat(jdbcTemplate.queryForObject("SELECT reverses_entry_id FROM journal_entries WHERE id = ?",
				UUID.class, reversalId)).isEqualTo(openingEntryId);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(accountId).minorUnits()).isZero();
		assertThat(balanceReader.projectionMatchesLedger(accountId)).isTrue();
		assertThatThrownBy(() -> ledgerWriter.reverseJournalEntry(openingEntryId))
				.isInstanceOf(IllegalStateException.class);

		accountService.closeAccount(accountId);
		assertThat(accountService.statusOf(accountId)).isEqualTo(AccountStatus.CLOSED);
		assertThatThrownBy(() -> accountService.simulateDeposit(accountId, Money.parsePositiveNzd("1.00")))
				.isInstanceOf(IllegalStateException.class);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_entries WHERE id = ?", Integer.class,
				openingEntryId)).isEqualTo(1);
	}

	@Test
	void accountSuspensionStopsFundingUntilTheAccountIsReactivated() {
		UUID accountId = accountService.openAccount(ownerCustomerId, "Lifecycle");
		accountService.suspendAccount(accountId);
		assertThat(accountService.statusOf(accountId)).isEqualTo(AccountStatus.SUSPENDED);
		assertThatThrownBy(() -> accountService.simulateDeposit(accountId, Money.parsePositiveNzd("1.00")))
				.isInstanceOf(IllegalStateException.class);

		accountService.reactivateAccount(accountId);
		accountService.simulateDeposit(accountId, Money.parsePositiveNzd("1.00"));
		assertThat(accountService.statusOf(accountId)).isEqualTo(AccountStatus.OPEN);
		assertThat(balanceReader.ledgerBalanceForCustomerAccount(accountId).minorUnits()).isEqualTo(100_100L);
	}

	@Test
	void aFailureAfterAccountAndJournalWritesRollsBackTheWholeOpeningTransaction() {
		int accountCountBefore = countRows("accounts");
		int ledgerAccountCountBefore = countRows("ledger_accounts");
		int journalCountBefore = countRows("journal_entries");
		int postingCountBefore = countRows("postings");
		int projectionCountBefore = countRows("account_balance_projection");
		jdbcTemplate.execute("""
				CREATE FUNCTION test_fail_ledgercore_posting() RETURNS TRIGGER LANGUAGE plpgsql AS $$
				BEGIN
					RAISE EXCEPTION 'forced failure during opening funding';
					RETURN NEW;
				END;
				$$
				""");
		jdbcTemplate.execute("""
				CREATE TRIGGER test_fail_ledgercore_posting
				BEFORE INSERT ON postings
				FOR EACH ROW EXECUTE FUNCTION test_fail_ledgercore_posting()
				""");
		try {
			assertThatThrownBy(() -> accountService.openAccount(ownerCustomerId, "Must roll back"))
					.isInstanceOf(DataAccessException.class);
		}
		finally {
			jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_fail_ledgercore_posting ON postings");
			jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_fail_ledgercore_posting()");
		}

		assertThat(countRows("accounts")).isEqualTo(accountCountBefore);
		assertThat(countRows("ledger_accounts")).isEqualTo(ledgerAccountCountBefore);
		assertThat(countRows("journal_entries")).isEqualTo(journalCountBefore);
		assertThat(countRows("postings")).isEqualTo(postingCountBefore);
		assertThat(countRows("account_balance_projection")).isEqualTo(projectionCountBefore);
	}

	private int journalCount(UUID accountId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM journal_entries WHERE reference = ?",
				Integer.class, "account:" + accountId);
	}

	private int postingCount(UUID accountId) {
		return jdbcTemplate.queryForObject("""
				SELECT count(*) FROM postings p JOIN journal_entries je ON je.id = p.journal_entry_id
				WHERE je.reference = ?
				""", Integer.class, "account:" + accountId);
	}

	private UUID customerLedgerAccountId(UUID accountId) {
		return jdbcTemplate.queryForObject(
				"SELECT id FROM ledger_accounts WHERE customer_account_id = ? AND kind = 'CUSTOMER_DEPOSIT'",
				UUID.class, accountId);
	}

	private int countRows(String tableName) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName, Integer.class);
	}
}
