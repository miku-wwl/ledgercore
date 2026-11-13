package nz.ac.ledgercore.ledger;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record JournalEntry(UUID id, JournalEntryType type, UUID reversesEntryId, List<Posting> postings) {

	public JournalEntry {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(postings, "postings");
		postings = List.copyOf(postings);
		if ((type == JournalEntryType.REVERSAL) != (reversesEntryId != null)) {
			throw new IllegalArgumentException("Only a reversal entry may reference the entry it reverses.");
		}
		if (postings.size() < 2) {
			throw new IllegalArgumentException("A journal entry requires at least two postings.");
		}

		Set<UUID> ledgerAccountIds = new HashSet<>();
		long debits = 0;
		long credits = 0;
		for (Posting posting : postings) {
			Objects.requireNonNull(posting, "posting");
			if (!ledgerAccountIds.add(posting.ledgerAccountId())) {
				throw new IllegalArgumentException("A journal entry may post only once to each ledger account.");
			}
			if (posting.side() == PostingSide.DEBIT) {
				debits = Math.addExact(debits, posting.amountMinor());
			}
			else {
				credits = Math.addExact(credits, posting.amountMinor());
			}
		}
		if (debits != credits) {
			throw new IllegalArgumentException("Journal entry debits must equal credits.");
		}
	}

	public static JournalEntry openingFunding(UUID reserveLedgerAccountId, UUID customerLedgerAccountId,
			Money amount) {
		return balancedFunding(JournalEntryType.OPENING_FUNDING, reserveLedgerAccountId, customerLedgerAccountId,
				amount);
	}

	public static JournalEntry simulatedDeposit(UUID reserveLedgerAccountId, UUID customerLedgerAccountId,
			Money amount) {
		return balancedFunding(JournalEntryType.SIMULATED_DEPOSIT, reserveLedgerAccountId, customerLedgerAccountId,
				amount);
	}

	public static JournalEntry transfer(UUID sourceLedgerAccountId, UUID targetLedgerAccountId, Money amount) {
		Objects.requireNonNull(amount, "amount");
		if (sourceLedgerAccountId.equals(targetLedgerAccountId)) {
			throw new IllegalArgumentException("A transfer must use two distinct ledger accounts.");
		}
		if (amount.minorUnits() <= 0) {
			throw new IllegalArgumentException("Transfer amount must be greater than zero.");
		}
		return new JournalEntry(UUID.randomUUID(), JournalEntryType.TRANSFER, null, List.of(
				new Posting(sourceLedgerAccountId, PostingSide.DEBIT, amount.minorUnits()),
				new Posting(targetLedgerAccountId, PostingSide.CREDIT, amount.minorUnits())));
	}

	public static JournalEntry reversalOf(JournalEntry original) {
		Objects.requireNonNull(original, "original");
		if (original.type() == JournalEntryType.REVERSAL) {
			throw new IllegalArgumentException("A reversal cannot itself be reversed in the MVP.");
		}
		List<Posting> reversedPostings = original.postings().stream()
			.map(posting -> new Posting(posting.ledgerAccountId(), posting.side().opposite(), posting.amountMinor()))
			.toList();
		return new JournalEntry(UUID.randomUUID(), JournalEntryType.REVERSAL, original.id(), reversedPostings);
	}

	private static JournalEntry balancedFunding(JournalEntryType type, UUID reserveLedgerAccountId,
			UUID customerLedgerAccountId, Money amount) {
		Objects.requireNonNull(amount, "amount");
		if (amount.minorUnits() <= 0) {
			throw new IllegalArgumentException("Funding amount must be greater than zero.");
		}
		return new JournalEntry(UUID.randomUUID(), type, null, List.of(
				new Posting(reserveLedgerAccountId, PostingSide.DEBIT, amount.minorUnits()),
				new Posting(customerLedgerAccountId, PostingSide.CREDIT, amount.minorUnits())));
	}
}
