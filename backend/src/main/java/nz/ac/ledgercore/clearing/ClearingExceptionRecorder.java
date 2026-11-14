package nz.ac.ledgercore.clearing;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClearingExceptionRecorder {

    private final JdbcTemplate jdbc;

    public ClearingExceptionRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordReserveInsufficient(UUID paymentId, UUID operationId, UUID organizationId) {
        Integer existing = jdbc.queryForObject("""
                SELECT count(*) FROM reconciliation_exceptions
                WHERE operation_id = ? AND category = 'CLEARING_RESERVE_INSUFFICIENT' AND status <> 'RESOLVED'
                """, Integer.class, operationId);
        if (existing != null && existing > 0) {
            return;
        }
        Long amount = jdbc.queryForObject("SELECT amount_minor FROM corporate_payment_intents WHERE id = ?",
                Long.class, paymentId);
        Long reserveBalance = jdbc.queryForObject("""
                SELECT balance_minor FROM account_balance_projection
                WHERE ledger_account_id = '00000000-0000-5000-8000-000000000001'
                """, Long.class);
        jdbc.update("""
                INSERT INTO reconciliation_exceptions (id, category, severity, operation_id, organization_id,
                    expected_facts, observed_facts)
                VALUES (?, 'CLEARING_RESERVE_INSUFFICIENT', 'CRITICAL', ?, ?,
                    jsonb_build_object('requiredMinor', ?), jsonb_build_object('availableMinor', ?))
                """, UUID.randomUUID(), operationId, organizationId, amount, reserveBalance);
    }
}
