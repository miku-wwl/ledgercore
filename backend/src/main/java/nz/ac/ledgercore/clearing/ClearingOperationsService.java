package nz.ac.ledgercore.clearing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public ClearingOperationsService(JdbcTemplate jdbc, OrganizationAuthorizationService authorization,
            ClearingSimulatorClient simulator, DurableEventStore events) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.simulator = simulator;
        this.events = events;
    }

    public OperationResponse inquire(UUID actorCustomerId, UUID operationId) {
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
        OperationResponse response = simulator.inquire(operationId);
        events.recordAudit(actorCustomerId, "CLEARING_STATUS_INQUIRY", "CLEARING_INSTRUCTION", operationId,
                organizationId, Map.of("externalStatus", response.externalStatus(),
                        "sequenceNo", response.sequenceNo()));
        return response;
    }
}
