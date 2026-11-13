package nz.ac.ledgercore.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerBalanceReader {

	private static final String LEDGER_BALANCE_SQL = """
			SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side
					THEN p.amount_minor ELSE -p.amount_minor END), 0)
			FROM ledger_accounts la
			LEFT JOIN postings p ON p.ledger_account_id = la.id
			WHERE la.id = ?
			GROUP BY la.id, la.normal_side
			""";

	private final JdbcTemplate jdbcTemplate;

	public LedgerBalanceReader(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(readOnly = true)
	public Money ledgerBalanceForCustomerAccount(UUID customerAccountId) {
		return ledgerBalanceForLedgerAccount(customerLedgerAccountId(customerAccountId));
	}

	@Transactional(readOnly = true)
	public Money projectionBalanceForCustomerAccount(UUID customerAccountId) {
		return projectionBalance(customerLedgerAccountId(customerAccountId));
	}

	@Transactional(readOnly = true)
	public Money ledgerBalanceForLedgerAccount(UUID ledgerAccountId) {
		return queryMoney(LEDGER_BALANCE_SQL, ledgerAccountId, "Ledger account");
	}

	@Transactional(readOnly = true)
	public Money projectionBalanceForLedgerAccount(UUID ledgerAccountId) {
		return projectionBalance(ledgerAccountId);
	}

	@Transactional(readOnly = true)
	public boolean projectionMatchesLedger(UUID customerAccountId) {
		UUID ledgerAccountId = customerLedgerAccountId(customerAccountId);
		return ledgerBalanceForLedgerAccount(ledgerAccountId).equals(projectionBalance(ledgerAccountId));
	}

	private Money projectionBalance(UUID ledgerAccountId) {
		return queryMoney("SELECT balance_minor FROM account_balance_projection WHERE ledger_account_id = ?",
				ledgerAccountId, "Balance projection");
	}

	private Money queryMoney(String sql, UUID id, String description) {
		List<BigDecimal> values = jdbcTemplate.query(sql, (resultSet, rowNumber) -> resultSet.getBigDecimal(1), id);
		if (values.isEmpty()) {
			throw new IllegalArgumentException(description + " does not exist: " + id);
		}
		try {
			return new Money(values.getFirst().longValueExact());
		}
		catch (ArithmeticException exception) {
			throw new IllegalStateException(description + " balance exceeds the supported NZD range.", exception);
		}
	}

	private UUID customerLedgerAccountId(UUID customerAccountId) {
		List<UUID> ids = jdbcTemplate.query(
				"SELECT id FROM ledger_accounts WHERE customer_account_id = ? AND kind = 'CUSTOMER_DEPOSIT'",
				(resultSet, rowNumber) -> resultSet.getObject(1, UUID.class), customerAccountId);
		if (ids.isEmpty()) {
			throw new IllegalArgumentException("Customer account has no ledger account: " + customerAccountId);
		}
		return ids.getFirst();
	}
}
