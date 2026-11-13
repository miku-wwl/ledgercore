package nz.ac.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JournalEntryTests {

	@Test
	void openingFundingBalancesTheSimulationReserveAndCustomerLiability() {
		UUID reserve = UUID.randomUUID();
		UUID customerDeposit = UUID.randomUUID();
		JournalEntry entry = JournalEntry.openingFunding(reserve, customerDeposit,
				Money.parsePositiveNzd("1000.00"));

		assertThat(entry.type()).isEqualTo(JournalEntryType.OPENING_FUNDING);
		assertThat(entry.postings()).containsExactly(
				new Posting(reserve, PostingSide.DEBIT, 100_000L),
				new Posting(customerDeposit, PostingSide.CREDIT, 100_000L));
	}

	@Test
	void rejectsUnbalancedDuplicateAndSingleAccountEntries() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), JournalEntryType.TRANSFER, null, List.of(
				new Posting(first, PostingSide.DEBIT, 500), new Posting(second, PostingSide.CREDIT, 499))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), JournalEntryType.TRANSFER, null, List.of(
				new Posting(first, PostingSide.DEBIT, 500), new Posting(first, PostingSide.CREDIT, 500))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void reversalIsANewEntryWithEveryPostingSideFlipped() {
		JournalEntry original = JournalEntry.openingFunding(UUID.randomUUID(), UUID.randomUUID(),
				Money.parsePositiveNzd("25.00"));
		JournalEntry reversal = JournalEntry.reversalOf(original);

		assertThat(reversal.id()).isNotEqualTo(original.id());
		assertThat(reversal.reversesEntryId()).isEqualTo(original.id());
		assertThat(reversal.type()).isEqualTo(JournalEntryType.REVERSAL);
		assertThat(reversal.postings()).containsExactly(
				new Posting(original.postings().get(0).ledgerAccountId(), PostingSide.CREDIT, 2_500L),
				new Posting(original.postings().get(1).ledgerAccountId(), PostingSide.DEBIT, 2_500L));
		assertThatThrownBy(() -> JournalEntry.reversalOf(reversal)).isInstanceOf(IllegalArgumentException.class);
	}
}
