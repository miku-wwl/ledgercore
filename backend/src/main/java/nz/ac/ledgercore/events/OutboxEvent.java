package nz.ac.ledgercore.events;

import java.util.UUID;

public record OutboxEvent(UUID id, String eventKey, String eventType, String aggregateType, UUID aggregateId,
        UUID actorCustomerId, UUID organizationId, String correlationId, String payloadJson, int attemptCount) {
}
