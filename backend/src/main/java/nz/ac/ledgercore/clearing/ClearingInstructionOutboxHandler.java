package nz.ac.ledgercore.clearing;

import java.util.List;
import java.util.UUID;

import nz.ac.ledgercore.clearing.ClearingDtos.OperationRequest;
import nz.ac.ledgercore.clearing.ClearingDtos.OperationResponse;
import nz.ac.ledgercore.events.OutboxEvent;
import nz.ac.ledgercore.events.OutboxEventHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgercore.clearing.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ClearingInstructionOutboxHandler implements OutboxEventHandler {

    private final JdbcTemplate jdbc;
    private final ClearingSimulatorClient simulator;

    public ClearingInstructionOutboxHandler(JdbcTemplate jdbc, ClearingSimulatorClient simulator) {
        this.jdbc = jdbc;
        this.simulator = simulator;
    }

    @Override
    public boolean supports(OutboxEvent event) {
        return "clearing.instruction.ready".equals(event.eventType());
    }

    @Override
    public void handle(OutboxEvent event) {
        List<OperationRequest> instructions = jdbc.query("""
                SELECT p.operation_id, p.id AS payment_id, p.intent_digest, p.amount_minor, p.currency,
                       p.beneficiary_account_reference, p.beneficiary_routing_code
                FROM corporate_payment_intents p
                JOIN clearing_instructions c ON c.payment_intent_id = p.id AND c.operation_id = p.operation_id
                WHERE p.operation_id = ? AND p.status IN ('PENDING_CLEARING', 'UNKNOWN', 'COMPLETED', 'REJECTED')
                  AND p.approved_digest = p.intent_digest AND c.intent_digest = p.intent_digest
                """, (rs, row) -> new OperationRequest(rs.getObject("operation_id", UUID.class),
                    rs.getObject("payment_id", UUID.class), rs.getString("intent_digest").trim(),
                    rs.getLong("amount_minor"), rs.getString("currency").trim(),
                    rs.getString("beneficiary_account_reference"), rs.getString("beneficiary_routing_code")),
                event.aggregateId());
        if (instructions.isEmpty()) {
            throw new IllegalStateException("A persisted approved clearing instruction is required.");
        }
        OperationRequest instruction = instructions.getFirst();
        try {
            OperationResponse response = simulator.submit(instruction);
            if (!instruction.operationId().equals(response.operationId())
                    || !instruction.paymentId().equals(response.paymentId())
                    || !instruction.intentDigest().equals(response.intentDigest())) {
                throw new IllegalStateException("Simulator response did not match the approved operation identity.");
            }
            jdbc.update("""
                    UPDATE clearing_instructions SET status = CASE WHEN status = 'READY' THEN 'SENT' ELSE status END,
                        simulator_reference = COALESCE(simulator_reference, ?), attempt_count = attempt_count + 1,
                        last_error = NULL, updated_at = clock_timestamp()
                    WHERE operation_id = ?
                    """, response.simulatorReference(), instruction.operationId());
        }
        catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE clearing_instructions SET attempt_count = attempt_count + 1,
                        last_error = ?, updated_at = clock_timestamp()
                    WHERE operation_id = ? AND status IN ('READY', 'SENT')
                    """, exception.getClass().getSimpleName(), instruction.operationId());
            throw exception;
        }
    }
}
