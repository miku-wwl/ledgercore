package nz.ac.ledgercore.transactionhistory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nz.ac.ledgercore.api.ApiNotFoundException;
import nz.ac.ledgercore.ledger.Money;

@Service
public class TransactionHistoryService {

	private final JdbcTemplate jdbcTemplate;

	public TransactionHistoryService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(readOnly = true)
	public TransactionPage list(UUID ownerCustomerId, UUID accountId, int requestedLimit, String cursor) {
		int limit = Math.clamp(requestedLimit, 1, 100);
		if (!ownsAccount(ownerCustomerId, accountId)) {
			throw new ApiNotFoundException("Account was not found.");
		}
		Cursor decoded = cursor == null || cursor.isBlank() ? null : decode(cursor);
		String sql = """
				SELECT t.id, t.source_account_id, t.target_account_id, t.amount_minor, t.currency,
				       t.created_at, source.display_name AS source_name, target.display_name AS target_name
				FROM transfers t
				JOIN accounts current_account ON current_account.id = ? AND current_account.owner_customer_id = ?
				JOIN accounts source ON source.id = t.source_account_id
				JOIN accounts target ON target.id = t.target_account_id
				WHERE (t.source_account_id = ? OR t.target_account_id = ?)
				""";
		if (decoded != null) {
			sql += " AND (t.created_at, t.id) < (?, ?)";
		}
		sql += " ORDER BY t.created_at DESC, t.id DESC LIMIT ?";
		List<TransactionView> rows;
		if (decoded == null) {
			rows = jdbcTemplate.query(sql, (rs, row) -> map(rs.getObject("id", UUID.class),
					rs.getObject("source_account_id", UUID.class), rs.getObject("target_account_id", UUID.class), accountId,
					rs.getLong("amount_minor"), rs.getString("currency"), rs.getTimestamp("created_at").toInstant(),
					rs.getString("source_name"), rs.getString("target_name")), accountId, ownerCustomerId, accountId,
					accountId, limit + 1);
		}
		else {
			rows = jdbcTemplate.query(sql, (rs, row) -> map(rs.getObject("id", UUID.class),
					rs.getObject("source_account_id", UUID.class), rs.getObject("target_account_id", UUID.class), accountId,
					rs.getLong("amount_minor"), rs.getString("currency"), rs.getTimestamp("created_at").toInstant(),
					rs.getString("source_name"), rs.getString("target_name")), accountId, ownerCustomerId, accountId,
					accountId, java.sql.Timestamp.from(decoded.createdAt()), decoded.id(), limit + 1);
		}
		boolean hasMore = rows.size() > limit;
		List<TransactionView> items = hasMore ? rows.subList(0, limit) : rows;
		String next = hasMore ? encode(items.getLast().createdAt(), items.getLast().id()) : null;
		return new TransactionPage(items, next);
	}

	@Transactional(readOnly = true)
	public TransactionDetail detail(UUID ownerCustomerId, UUID accountId, UUID transferId) {
		List<TransferBase> transfers = jdbcTemplate.query("""
				SELECT t.id, t.journal_entry_id, t.amount_minor, t.currency, t.created_at,
				       t.source_account_id, t.target_account_id
				FROM transfers t
				JOIN accounts current_account ON current_account.id = ? AND current_account.owner_customer_id = ?
				WHERE t.id = ? AND (t.source_account_id = ? OR t.target_account_id = ?)
				""", (rs, row) -> new TransferBase(rs.getObject("id", UUID.class),
					rs.getObject("journal_entry_id", UUID.class), rs.getLong("amount_minor"), rs.getString("currency"),
					rs.getTimestamp("created_at").toInstant(), rs.getObject("source_account_id", UUID.class),
					rs.getObject("target_account_id", UUID.class)), accountId, ownerCustomerId, transferId, accountId, accountId);
		if (transfers.isEmpty()) {
			throw new ApiNotFoundException("Transaction was not found.");
		}
		TransferBase transfer = transfers.getFirst();
		List<PostingView> postings = jdbcTemplate.query("""
				SELECT p.side, p.amount_minor,
				       CASE WHEN la.customer_account_id = ? THEN 'This account' ELSE 'Counterparty' END AS account_label
				FROM postings p JOIN ledger_accounts la ON la.id = p.ledger_account_id
				WHERE p.journal_entry_id = ?
				ORDER BY account_label, p.side
				""", (rs, row) -> new PostingView(rs.getString("account_label"), rs.getString("side"),
				new Money(rs.getLong("amount_minor")).toNzdString(), transfer.currency()), accountId,
			transfer.journalEntryId());
		return new TransactionDetail(transfer.id(), transfer.sourceAccountId().equals(accountId) ? "OUTGOING" : "INCOMING",
				new Money(transfer.amountMinor()).toNzdString(), transfer.currency(), transfer.createdAt(), "POSTED", postings,
				"Debits and credits are accounting directions. They are not the same as money moving out of or into the UI account.");
	}

	private boolean ownsAccount(UUID ownerCustomerId, UUID accountId) {
		Boolean exists = jdbcTemplate.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM accounts WHERE id = ? AND owner_customer_id = ?)", Boolean.class,
				accountId, ownerCustomerId);
		return Boolean.TRUE.equals(exists);
	}

	private TransactionView map(UUID id, UUID sourceId, UUID targetId, UUID accountId, long amountMinor,
			String currency, Instant createdAt, String sourceName, String targetName) {
		boolean outgoing = sourceId.equals(accountId);
		return new TransactionView(id, outgoing ? "OUTGOING" : "INCOMING",
				new Money(amountMinor).toNzdString(), currency, createdAt, "POSTED",
				outgoing ? targetName : sourceName);
	}

	private Cursor decode(String encoded) {
		try {
			String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
			String[] parts = value.split("\\|", -1);
			if (parts.length != 2) {
				throw new IllegalArgumentException("Invalid transaction cursor.");
			}
			return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("Invalid transaction cursor.", exception);
		}
	}

	private String encode(Instant createdAt, UUID id) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString((createdAt.toString() + "|" + id).getBytes(StandardCharsets.UTF_8));
	}

	private record Cursor(Instant createdAt, UUID id) {
	}

	private record TransferBase(UUID id, UUID journalEntryId, long amountMinor, String currency, Instant createdAt,
			UUID sourceAccountId, UUID targetAccountId) {
	}

	public record TransactionView(UUID id, String direction, String amount, String currency, Instant createdAt,
			String status, String counterparty) {
	}

	public record TransactionPage(List<TransactionView> items, String nextCursor) {
	}

	public record PostingView(String account, String side, String amount, String currency) {
	}

	public record TransactionDetail(UUID id, String direction, String amount, String currency, Instant createdAt,
			String status, List<PostingView> postings, String accountingExplanation) {
	}
}
