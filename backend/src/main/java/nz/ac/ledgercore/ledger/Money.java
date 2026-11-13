package nz.ac.ledgercore.ledger;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/** Exact NZD value in the currency's smallest supported unit (cents). */
public record Money(long minorUnits) {

	private static final Pattern CANONICAL_NZD = Pattern.compile("(?:0|[1-9][0-9]*)\\.[0-9]{2}");

	public static Money parsePositiveNzd(String value) {
		if (value == null || !CANONICAL_NZD.matcher(value).matches()) {
			throw new IllegalArgumentException("Amount must be a canonical NZD decimal string with two digits.");
		}
		try {
			long minorUnits = new BigDecimal(value).movePointRight(2).longValueExact();
			if (minorUnits <= 0) {
				throw new IllegalArgumentException("Amount must be greater than zero.");
			}
			return new Money(minorUnits);
		}
		catch (ArithmeticException exception) {
			throw new IllegalArgumentException("Amount exceeds the supported NZD range.", exception);
		}
	}

	public Money plus(Money other) {
		Objects.requireNonNull(other, "other");
		return new Money(Math.addExact(minorUnits, other.minorUnits));
	}

	public Money minus(Money other) {
		Objects.requireNonNull(other, "other");
		return new Money(Math.subtractExact(minorUnits, other.minorUnits));
	}

	public String toNzdString() {
		return BigDecimal.valueOf(minorUnits, 2).toPlainString();
	}
}
