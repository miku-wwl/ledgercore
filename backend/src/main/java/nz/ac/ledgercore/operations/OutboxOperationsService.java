package nz.ac.ledgercore.operations;

import java.util.List;
import java.util.UUID;

import nz.ac.ledgercore.events.DurableEventStore;
import nz.ac.ledgercore.events.DurableEventStore.OutboxStatusView;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationService;
import nz.ac.ledgercore.organizations.OrganizationRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxOperationsService {

    private final OrganizationAuthorizationService authorizationService;
    private final DurableEventStore eventStore;

    public OutboxOperationsService(OrganizationAuthorizationService authorizationService,
            DurableEventStore eventStore) {
        this.authorizationService = authorizationService;
        this.eventStore = eventStore;
    }

    @Transactional(readOnly = true)
    public List<OutboxStatusView> list(UUID actorCustomerId, UUID organizationId, int limit) {
        authorizationService.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
        return eventStore.outboxForOrganization(organizationId, limit);
    }

    @Transactional
    public RetryResult retry(UUID actorCustomerId, UUID organizationId, UUID eventId) {
        authorizationService.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
        int previousAttempts = eventStore.retryDeadLetter(eventId, organizationId, actorCustomerId);
        return new RetryResult(eventId, "PENDING", previousAttempts);
    }

    public record RetryResult(UUID eventId, String status, int previousAttemptCount) {
    }
}
