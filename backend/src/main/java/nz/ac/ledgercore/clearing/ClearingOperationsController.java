package nz.ac.ledgercore.clearing;

import java.util.UUID;

import nz.ac.ledgercore.clearing.ClearingDtos.OperationResponse;
import nz.ac.ledgercore.identity.CustomerPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "ledgercore.clearing.enabled", havingValue = "true")
public class ClearingOperationsController {

    private final ClearingOperationsService operations;

    public ClearingOperationsController(ClearingOperationsService operations) {
        this.operations = operations;
    }

    @PostMapping("/api/operations/clearing/{operationId}/inquiry")
    OperationResponse inquire(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID operationId) {
        return operations.inquire(principal.customerId(), operationId);
    }
}
