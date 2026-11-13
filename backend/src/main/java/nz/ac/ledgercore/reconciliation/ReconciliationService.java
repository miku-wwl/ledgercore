package nz.ac.ledgercore.reconciliation;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nz.ac.ledgercore.api.ApiNotFoundException;
import nz.ac.ledgercore.ledger.LedgerBalanceReader;
import nz.ac.ledgercore.ledger.Money;

@Service
public class ReconciliationService {

	private final JdbcTemplate jdbcTemplate;
	private final LedgerBalanceReader ledgerBalanceReader;

	public ReconciliationService(JdbcTemplate jdbcTemplate, LedgerBalanceReader ledgerBalanceReader) {
		this.jdbcTemplate = jdbcTemplate;
		this.ledgerBalanceReader = ledgerBalanceReader;
	}

	@Transactional(readOnly = true)
	public ReconciliationResult reconcile(UUID ownerCustomerId, UUID accountId) {
		List<UUID> accounts = jdbcTemplate.query("SELECT id FROM accounts WHERE owner_customer_id = ? AND id = ?",
				(rs, row) -> rs.getObject(1, UUID.class), ownerCustomerId, accountId);
		if (accounts.isEmpty()) {
			throw new ApiNotFoundException("Account was not found.");
		}
		Money ledger = ledgerBalanceReader.ledgerBalanceForCustomerAccount(accountId);
		Money projection = ledgerBalanceReader.projectionBalanceForCustomerAccount(accountId);
		return new ReconciliationResult(accountId, ledger.toNzdString(), projection.toNzdString(), ledger.equals(projection));
	}

	public record ReconciliationResult(UUID accountId, String authoritativeLedgerBalance,
			String projectedBalance, boolean matches) {
	}
}
