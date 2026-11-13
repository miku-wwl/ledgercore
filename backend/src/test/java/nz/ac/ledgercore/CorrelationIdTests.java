package nz.ac.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CorrelationIdTests {

	@Test
	void preservesAValidCallerProvidedId() {
		assertThat(CorrelationIdFilter.resolveCorrelationId("req-2026.11-01")).isEqualTo("req-2026.11-01");
	}

	@Test
	void generatesUuidForMissingOrMalformedIds() {
		assertThat(UUID.fromString(CorrelationIdFilter.resolveCorrelationId(null))).isNotNull();
		assertThat(UUID.fromString(CorrelationIdFilter.resolveCorrelationId("invalid id"))).isNotNull();
		assertThat(UUID.fromString(CorrelationIdFilter.resolveCorrelationId("forged\nheader"))).isNotNull();
	}
}
