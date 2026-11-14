package nz.ac.ledgercore.clearing.simulator;

import java.util.List;
import java.util.UUID;

import nz.ac.ledgercore.clearing.simulator.SimulatorCallbackDispatcher.CallbackRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimulatorCallbackStore {

    private static final int MAX_ATTEMPTS = 8;
    private final JdbcTemplate jdbc;

    public SimulatorCallbackStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public CallbackRow claimNext() {
        List<CallbackRow> rows = jdbc.query("""
                SELECT c.callback_id, c.operation_id, c.attempt_count, c.payload::text AS payload,
                       o.correlation_id
                FROM simulator_callback_outbox c
                JOIN simulator_operations o ON o.operation_id = c.operation_id
                WHERE ((c.delivery_status = 'PENDING' AND c.next_attempt_at <= clock_timestamp())
                    OR (c.delivery_status = 'PROCESSING' AND c.lease_until < clock_timestamp()))
                    AND c.attempt_count < ?
                    AND NOT EXISTS (
                        SELECT 1 FROM simulator_callback_outbox earlier
                        WHERE earlier.operation_id = c.operation_id
                          AND earlier.sequence_no < c.sequence_no
                          AND earlier.delivery_status <> 'DELIVERED'
                    )
                ORDER BY c.created_at, c.callback_id
                FOR UPDATE SKIP LOCKED LIMIT 1
                """, (rs, row) -> new CallbackRow(rs.getObject("callback_id", UUID.class),
                    rs.getObject("operation_id", UUID.class), rs.getInt("attempt_count") + 1,
                    rs.getString("correlation_id"), rs.getString("payload")), MAX_ATTEMPTS);
        if (rows.isEmpty()) {
            jdbc.update("""
                    UPDATE simulator_callback_outbox SET delivery_status = 'DEAD', lease_until = NULL,
                        last_error = COALESCE(last_error, 'Delivery lease expired after retry limit.')
                    WHERE delivery_status = 'PROCESSING' AND lease_until < clock_timestamp()
                      AND attempt_count >= ?
                    """, MAX_ATTEMPTS);
            return null;
        }
        CallbackRow row = rows.getFirst();
        jdbc.update("""
                UPDATE simulator_callback_outbox SET delivery_status = 'PROCESSING', attempt_count = ?,
                    lease_until = clock_timestamp() + interval '30 seconds', last_error = NULL
                WHERE callback_id = ?
                """, row.attemptCount(), row.callbackId());
        return row;
    }

    @Transactional
    public void markDelivered(UUID callbackId) {
        jdbc.update("""
                UPDATE simulator_callback_outbox SET delivery_status = 'DELIVERED', delivered_at = clock_timestamp(),
                    lease_until = NULL, last_error = NULL
                WHERE callback_id = ? AND delivery_status = 'PROCESSING'
                """, callbackId);
    }

    @Transactional
    public void markFailed(CallbackRow row, String safeError) {
        if (row.attemptCount() >= MAX_ATTEMPTS) {
            jdbc.update("""
                    UPDATE simulator_callback_outbox SET delivery_status = 'DEAD', lease_until = NULL,
                        last_error = ? WHERE callback_id = ? AND delivery_status = 'PROCESSING'
                    """, safeError, row.callbackId());
            return;
        }
        int seconds = Math.min(300, 1 << Math.min(8, row.attemptCount()));
        jdbc.update("""
                UPDATE simulator_callback_outbox SET delivery_status = 'PENDING', lease_until = NULL,
                    next_attempt_at = clock_timestamp() + (? * interval '1 second'), last_error = ?
                WHERE callback_id = ? AND delivery_status = 'PROCESSING'
                """, seconds, safeError, row.callbackId());
    }
}
