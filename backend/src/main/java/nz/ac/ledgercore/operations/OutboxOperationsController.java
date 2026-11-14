package nz.ac.ledgercore.operations;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import nz.ac.ledgercore.events.DurableEventStore.OutboxStatusView;
import nz.ac.ledgercore.identity.CustomerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class OutboxOperationsController {

    private final OutboxOperationsService operationsService;

    public OutboxOperationsController(OutboxOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @Operation(operationId = "listOrganizationOutboxEvents")
    @GetMapping("/api/organizations/{organizationId}/operations/outbox")
    List<OutboxStatusView> list(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId, @RequestParam(defaultValue = "50") int limit) {
        return operationsService.list(principal.customerId(), organizationId, limit);
    }

    @Operation(operationId = "retryOrganizationDeadLetterEvent")
    @PostMapping("/api/organizations/{organizationId}/operations/outbox/{eventId}/retry")
    OutboxOperationsService.RetryResult retry(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId, @PathVariable UUID eventId) {
        return operationsService.retry(principal.customerId(), organizationId, eventId);
    }
}
