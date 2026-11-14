package nz.ac.ledgercore.payroll;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nz.ac.ledgercore.identity.CustomerPrincipal;
import nz.ac.ledgercore.payroll.PayrollViews.BatchView;
import nz.ac.ledgercore.payroll.PayrollViews.ItemView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class PayrollController {

    private final PayrollService payrollService;
    private final PayrollExecutionService executionService;

    public PayrollController(PayrollService payrollService, PayrollExecutionService executionService) {
        this.payrollService = payrollService;
        this.executionService = executionService;
    }

    @Operation(operationId = "listOrganizationPayrollBatches")
    @GetMapping("/api/organizations/{organizationId}/payroll/batches")
    List<BatchView> list(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId) {
        return payrollService.listForOrganization(principal.customerId(), organizationId);
    }

    @Operation(operationId = "createPayrollBatch")
    @ApiResponse(responseCode = "201", description = "Draft payroll batch created")
    @PostMapping("/api/organizations/{organizationId}/payroll/batches")
    ResponseEntity<BatchView> create(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId, @Valid @RequestBody CreateBatchRequest request) {
        BatchView batch = payrollService.createDraft(principal.customerId(), organizationId, request.name(),
                request.payerAccountId());
        return ResponseEntity.created(URI.create("/api/payroll/batches/" + batch.id())).body(batch);
    }

    @Operation(operationId = "getPayrollBatch")
    @GetMapping("/api/payroll/batches/{batchId}")
    BatchView get(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID batchId) {
        return payrollService.get(principal.customerId(), batchId);
    }

    @Operation(operationId = "listPayrollBatchItems")
    @GetMapping("/api/payroll/batches/{batchId}/items")
    List<ItemView> items(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID batchId) {
        return payrollService.items(principal.customerId(), batchId);
    }

    @Operation(operationId = "addPayrollBatchItems")
    @PostMapping("/api/payroll/batches/{batchId}/items")
    BatchView addItems(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID batchId,
            @Valid @RequestBody AddItemsRequest request) {
        List<PayrollService.NewPayrollItem> additions = request.items().stream()
                .map(item -> new PayrollService.NewPayrollItem(item.employeeReference(), item.employeeName(),
                        item.payeeAccountId(), item.amount()))
                .toList();
        return payrollService.addItems(principal.customerId(), batchId, additions);
    }

    @Operation(operationId = "submitPayrollBatch")
    @PostMapping("/api/payroll/batches/{batchId}/submit")
    BatchView submit(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID batchId) {
        return payrollService.submit(principal.customerId(), batchId);
    }

    @Operation(operationId = "approvePayrollBatch")
    @PostMapping("/api/payroll/batches/{batchId}/approve")
    BatchView approve(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID batchId) {
        return payrollService.approve(principal.customerId(), batchId);
    }

    @Operation(operationId = "rejectPayrollBatch")
    @PostMapping("/api/payroll/batches/{batchId}/reject")
    BatchView reject(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID batchId,
            @Valid @RequestBody RejectBatchRequest request) {
        return payrollService.reject(principal.customerId(), batchId, request.reason());
    }

    @Operation(operationId = "executePayrollBatch")
    @PostMapping("/api/payroll/batches/{batchId}/execute")
    BatchView execute(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID batchId,
            @RequestParam(defaultValue = "500") int limit) {
        return executionService.execute(principal.customerId(), batchId, limit);
    }

    public record CreateBatchRequest(@NotBlank @Size(max = 120) String name, @NotNull UUID payerAccountId) {
    }

    public record AddItemsRequest(@NotEmpty @Size(max = 1_000) List<@Valid PayrollItemRequest> items) {
    }

    public record PayrollItemRequest(@NotBlank @Size(max = 80) String employeeReference,
            @NotBlank @Size(max = 120) String employeeName, @NotNull UUID payeeAccountId,
            @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]*)\\.[0-9]{2}") String amount) {
    }

    public record RejectBatchRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
