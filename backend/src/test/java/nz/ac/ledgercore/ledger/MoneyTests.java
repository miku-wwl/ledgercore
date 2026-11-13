package nz.ac.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTests {

	@Test
	void parsesAndFormatsExactMinorUnits() {
		Money amount = Money.parsePositiveNzd("120.05");

		assertThat(amount.minorUnits()).isEqualTo(12_005L);
		assertThat(amount.toNzdString()).isEqualTo("120.05");
	}

	@Test
	void rejectsZeroNegativeNonCanonicalAndOverflowAmounts() {
		for (String value : new String[] { "0.00", "-1.00", "+1.00", "1", "1.000", "01.00", "1e2", "1.0" }) {
			assertThatThrownBy(() -> Money.parsePositiveNzd(value))
					.as("amount %s must be rejected", value)
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> Money.parsePositiveNzd("92233720368547758.08"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void detectsArithmeticOverflow() {
		assertThatThrownBy(() -> new Money(Long.MAX_VALUE).plus(new Money(1)))
				.isInstanceOf(ArithmeticException.class);
	}
}
