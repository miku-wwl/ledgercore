package nz.ac.ledgercore.accounts;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nz.ac.ledgercore.accounts.domain.Account;
import nz.ac.ledgercore.accounts.persistence.AccountEntity;
import nz.ac.ledgercore.accounts.persistence.AccountRepository;
import nz.ac.ledgercore.ledger.LedgerBalanceReader;
import nz.ac.ledgercore.ledger.LedgerWriter;
import nz.ac.ledgercore.ledger.Money;

@Service
public class AccountService {

	public static final Money INITIAL_FUNDING = new Money(100_000L);

	private final AccountRepository accountRepository;
	private final LedgerWriter ledgerWriter;
	private final LedgerBalanceReader ledgerBalanceReader;

	public AccountService(AccountRepository accountRepository, LedgerWriter ledgerWriter,
			LedgerBalanceReader ledgerBalanceReader) {
		this.accountRepository = accountRepository;
		this.ledgerWriter = ledgerWriter;
		this.ledgerBalanceReader = ledgerBalanceReader;
	}

	@Transactional
	public UUID openAccount(UUID ownerCustomerId, String displayName) {
		Account account = Account.open(UUID.randomUUID(), ownerCustomerId, displayName);
		accountRepository.saveAndFlush(new AccountEntity(account));
		ledgerWriter.openCustomerDepositLedger(account.id(), INITIAL_FUNDING);
		return account.id();
	}

	@Transactional
	public UUID simulateDeposit(UUID customerAccountId, Money amount) {
		return ledgerWriter.recordSimulatedDeposit(customerAccountId, amount);
	}

	@Transactional
	public void suspendAccount(UUID customerAccountId) {
		ledgerWriter.lockCustomerAccountLedger(customerAccountId);
		AccountEntity entity = accountForUpdate(customerAccountId);
		entity.transitionTo(entity.toDomain().suspend());
		accountRepository.flush();
	}

	@Transactional
	public void reactivateAccount(UUID customerAccountId) {
		ledgerWriter.lockCustomerAccountLedger(customerAccountId);
		AccountEntity entity = accountForUpdate(customerAccountId);
		entity.transitionTo(entity.toDomain().reactivate());
		accountRepository.flush();
	}

	@Transactional
	public void closeAccount(UUID customerAccountId) {
		ledgerWriter.lockCustomerAccountLedger(customerAccountId);
		AccountEntity entity = accountForUpdate(customerAccountId);
		if (!ledgerBalanceReader.projectionMatchesLedger(customerAccountId)) {
			throw new IllegalStateException("Account projection does not match the authoritative ledger.");
		}
		long balanceMinor = ledgerBalanceReader.ledgerBalanceForCustomerAccount(customerAccountId).minorUnits();
		entity.transitionTo(entity.toDomain().close(balanceMinor));
		accountRepository.flush();
	}

	@Transactional(readOnly = true)
	public AccountStatus statusOf(UUID customerAccountId) {
		return accountRepository.findById(customerAccountId)
				.map(AccountEntity::toDomain)
				.map(Account::status)
				.orElseThrow(() -> new IllegalArgumentException("Customer account does not exist: " + customerAccountId));
	}

	@Transactional(readOnly = true)
	public boolean belongsTo(UUID ownerCustomerId, UUID customerAccountId) {
		return accountRepository.existsByIdAndOwnerCustomerId(customerAccountId, ownerCustomerId);
	}

	@Transactional(readOnly = true)
	public void assertOpen(UUID customerAccountId) {
		if (statusOf(customerAccountId) != AccountStatus.OPEN) {
			throw new IllegalStateException("Customer account is not open: " + customerAccountId);
		}
	}

	private AccountEntity accountForUpdate(UUID customerAccountId) {
		return accountRepository.findByIdForUpdate(customerAccountId)
				.orElseThrow(() -> new IllegalArgumentException("Customer account does not exist: " + customerAccountId));
	}
}
