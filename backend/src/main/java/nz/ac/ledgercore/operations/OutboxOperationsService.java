package nz.ac.ledgercore.operations;

import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meterRegistry;

    public OutboxOperationsService(OrganizationAuthorizationService authorizationService,
            DurableEventStore eventStore, MeterRegistry meterRegistry) {
        this.authorizationService = authorizationService;
        this.eventStore = eventStore;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(readOnly = true)
    public List<OutboxStatusView> list(UUID actorCustomerId, UUID organizationId, int limit) {
        authorizationService.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
        return eventStore.outboxForOrganization(organizationId, limit);
    }

    @Transactional
    public RetryResult retry(UUID actorCustomerId, UUID organizationId, UUID eventId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
        authorizationService.requireRole(actorCustomerId, organizationId, OrganizationRole.OPERATIONS);
        int previousAttempts = eventStore.retryDeadLetter(eventId, organizationId, actorCustomerId);
        return new RetryResult(eventId, "PENDING", previousAttempts);
        }
        catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        }
        finally {
            sample.stop(meterRegistry.timer("ledgercore.recovery.duration", "kind", "outbox_retry",
                    "outcome", outcome));
        }
    }

    public record RetryResult(UUID eventId, String status, int previousAttemptCount) {
    }
}
