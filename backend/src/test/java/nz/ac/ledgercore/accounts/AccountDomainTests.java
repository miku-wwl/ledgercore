package nz.ac.ledgercore.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import nz.ac.ledgercore.accounts.domain.Account;

class AccountDomainTests {

	@Test
	void accountLifecycleUsesExplicitLegalTransitions() {
		Account account = Account.open(UUID.randomUUID(), UUID.randomUUID(), "  Everyday  ");
		Account suspended = account.suspend();
		Account activeAgain = suspended.reactivate();
		Account closed = activeAgain.close(0);

		assertThat(account.displayName()).isEqualTo("Everyday");
		assertThat(suspended.status()).isEqualTo(AccountStatus.SUSPENDED);
		assertThat(activeAgain.status()).isEqualTo(AccountStatus.OPEN);
		assertThat(closed.status()).isEqualTo(AccountStatus.CLOSED);
		assertThat(closed.version()).isEqualTo(3);
	}

	@Test
	void fundedOrClosedAccountsCannotBeClosedOrReopened() {
		Account account = Account.open(UUID.randomUUID(), UUID.randomUUID(), "Everyday");
		assertThatThrownBy(() -> account.close(1)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> account.close(100_000)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> account.close(0).reactivate()).isInstanceOf(IllegalStateException.class);
	}
}
