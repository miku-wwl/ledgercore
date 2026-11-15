package nz.ac.ledgercore.clearing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import nz.ac.ledgercore.clearing.ClearingDtos.OperationResponse;
import nz.ac.ledgercore.events.DurableEventStore;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "ledgercore.clearing.enabled", havingValue = "true")
public class ClearingOperationsService {

    private final JdbcTemplate jdbc;
    private final OrganizationAuthorizationService authorization;
    private final ClearingSimulatorClient simulator;
    private final DurableEventStore events;
    private final MeterRegistry meterRegistry;

    public ClearingOperationsService(JdbcTemplate jdbc, OrganizationAuthorizationService authorization,
            ClearingSimulatorClient simulator, DurableEventStore events, MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.simulator = simulator;
        this.events = events;
        this.meterRegistry = meterRegistry;
    }

    public OperationResponse inquire(UUID actorCustomerId, UUID operationId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
        List<UUID> organizations = jdbc.query("""
                SELECT p.organization_id FROM corporate_payment_intents p
                JOIN clearing_instructions c ON c.operation_id = p.operation_id
                WHERE p.operation_id = ?
                """, (rs, row) -> rs.getObject(1, UUID.class), operationId);
        if (organizations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Clearing operation was not found.");
        }
        UUID organizationId = organizations.getFirst();
        authorization.requireCanOperateClearing(actorCustomerId, organizationId);
        // The highest callback sequence the bank holds: after a restore it can be behind what the simulator delivered.
        Long bankSequence = jdbc.queryForObject(
                "SELECT COALESCE(max(sequence_no), 0) FROM clearing_callback_inbox WHERE operation_id = ?", Long.class,
                operationId);
        long held = bankSequence == null ? 0 : bankSequence;
        OperationResponse response = simulator.inquire(operationId, held);
        events.recordAudit(actorCustomerId, "CLEARING_STATUS_INQUIRY", "CLEARING_INSTRUCTION", operationId,
                organizationId, Map.of("externalStatus", response.externalStatus(),
                        "sequenceNo", response.sequenceNo(), "bankSequence", held));
        return response;
        }
        catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        }
        finally {
            sample.stop(meterRegistry.timer("ledgercore.recovery.duration", "kind", "clearing_inquiry",
                    "outcome", outcome));
        }
    }
}
