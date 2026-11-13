package nz.ac.ledgercore.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CustomerPrincipalTests {

	@Test
	void stringRepresentationOmitsEmailAndPasswordHash() {
		String email = "private@example.test";
		String passwordHash = "$2a$12$test-password-hash";
		CustomerPrincipal principal = new CustomerPrincipal(UUID.randomUUID(), email, passwordHash, "Private Customer");

		assertThat(principal.toString()).doesNotContain(email, passwordHash);
		assertThat(principal.getPassword()).isEqualTo(passwordHash);
	}
}
