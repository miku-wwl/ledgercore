package nz.ac.ledgercore.operations;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import nz.ac.ledgercore.identity.CustomerPrincipal;
import nz.ac.ledgercore.operations.OperationsConsoleService.AuditEventView;
import nz.ac.ledgercore.operations.OperationsConsoleService.OperationSearchResult;
import nz.ac.ledgercore.operations.OperationsConsoleService.OperationsMetrics;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class OperationsConsoleController {

    private final OperationsConsoleService console;

    public OperationsConsoleController(OperationsConsoleService console) {
        this.console = console;
    }

    @Operation(operationId = "getOrganizationOperationsMetrics")
    @GetMapping("/api/organizations/{organizationId}/operations/metrics")
    OperationsMetrics metrics(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId) {
        return console.metrics(principal.customerId(), organizationId);
    }

    @Operation(operationId = "listOrganizationOperationsAuditHistory")
    @GetMapping("/api/organizations/{organizationId}/operations/audit")
    List<AuditEventView> auditHistory(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId, @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit) {
        return console.auditHistory(principal.customerId(), organizationId, limit);
    }

    @Operation(operationId = "searchOrganizationOperations")
    @GetMapping("/api/organizations/{organizationId}/operations/search")
    List<OperationSearchResult> search(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId, @RequestParam @NotBlank @Size(min = 3, max = 80) String query) {
        return console.search(principal.customerId(), organizationId, query);
    }
}
