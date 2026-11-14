package nz.ac.ledgercore.accounts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import nz.ac.ledgercore.ledger.Money;

@Service
public class AccountsQueryService {

    private final JdbcTemplate jdbcTemplate;

    public AccountsQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<AccountView> listForOwner(UUID ownerCustomerId) {
        return jdbcTemplate.query("""
                SELECT a.id, a.display_name, a.status, a.currency, p.balance_minor, a.created_at
                FROM accounts a
                JOIN ledger_accounts la ON la.customer_account_id = a.id AND la.kind = 'CUSTOMER_DEPOSIT'
                JOIN account_balance_projection p ON p.ledger_account_id = la.id
                WHERE a.owner_customer_id = ?
                ORDER BY a.created_at DESC, a.id DESC
                """, (rs, row) -> accountView(rs), ownerCustomerId);
    }

    @Transactional(readOnly = true)
    public Optional<AccountView> findForOwner(UUID ownerCustomerId, UUID accountId) {
        return queryOne("""
                SELECT a.id, a.display_name, a.status, a.currency, p.balance_minor, a.created_at
                FROM accounts a
                JOIN ledger_accounts la ON la.customer_account_id = a.id AND la.kind = 'CUSTOMER_DEPOSIT'
                JOIN account_balance_projection p ON p.ledger_account_id = la.id
                WHERE a.owner_customer_id = ? AND a.id = ?
                """, ownerCustomerId, accountId);
    }

    @Transactional(readOnly = true)
    public List<AccountView> listForOrganization(UUID organizationId) {
        return jdbcTemplate.query("""
                SELECT a.id, a.display_name, a.status, a.currency, p.balance_minor, a.created_at
                FROM accounts a
                JOIN ledger_accounts la ON la.customer_account_id = a.id AND la.kind = 'CUSTOMER_DEPOSIT'
                JOIN account_balance_projection p ON p.ledger_account_id = la.id
                WHERE a.owner_organization_id = ?
                ORDER BY a.created_at DESC, a.id DESC
                """, (rs, row) -> accountView(rs), organizationId);
    }

    @Transactional(readOnly = true)
    public Optional<AccountView> findForOrganization(UUID organizationId, UUID accountId) {
        return queryOne("""
                SELECT a.id, a.display_name, a.status, a.currency, p.balance_minor, a.created_at
                FROM accounts a
                JOIN ledger_accounts la ON la.customer_account_id = a.id AND la.kind = 'CUSTOMER_DEPOSIT'
                JOIN account_balance_projection p ON p.ledger_account_id = la.id
                WHERE a.owner_organization_id = ? AND a.id = ?
                """, organizationId, accountId);
    }

    private Optional<AccountView> queryOne(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, row) -> accountView(rs), args).stream().findFirst();
    }

    private static AccountView accountView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AccountView(rs.getObject("id", UUID.class), rs.getString("display_name"),
                rs.getString("status"), rs.getString("currency"),
                new Money(rs.getLong("balance_minor")).toNzdString(), rs.getTimestamp("created_at").toInstant());
    }

    public record AccountView(UUID id, String displayName, String status, String currency, String balance,
            Instant createdAt) {
    }
}
