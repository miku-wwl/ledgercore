package nz.ac.ledgercore.payroll;

import java.util.UUID;

import nz.ac.ledgercore.api.E6FaultInjectionHook;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PayrollExecutionService {

    private static final int MAX_ITEMS_PER_REQUEST = 1_000;

    private final PayrollService payrollService;
    private final OrganizationAuthorizationService authorizationService;
    private final PayrollItemExecutor itemExecutor;
    private final ObjectProvider<E6FaultInjectionHook> e6FaultLab;

    public PayrollExecutionService(PayrollService payrollService,
            OrganizationAuthorizationService authorizationService, PayrollItemExecutor itemExecutor,
            ObjectProvider<E6FaultInjectionHook> e6FaultLab) {
        this.payrollService = payrollService;
        this.authorizationService = authorizationService;
        this.itemExecutor = itemExecutor;
        this.e6FaultLab = e6FaultLab;
    }

    /** The orchestration method is deliberately non-transactional: each executor call commits one item. */
    public PayrollViews.BatchView execute(UUID actorCustomerId, UUID batchId, int maxItems) {
        if (maxItems < 1 || maxItems > MAX_ITEMS_PER_REQUEST) {
            throw new IllegalArgumentException("Execution limit must be between 1 and 1,000 payroll items.");
        }
        PayrollViews.BatchView current = payrollService.get(actorCustomerId, batchId);
        authorizationService.requireCanExecutePayroll(actorCustomerId, current.organizationId());
        if (isTerminal(current.status())) {
            return current;
        }
        if ("REJECTED".equals(current.status()) || "DRAFT".equals(current.status())
                || "SUBMITTED".equals(current.status())) {
            throw new PayrollException("BATCH_NOT_APPROVED", "Only an approved payroll batch can execute.",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
        current = payrollService.startProcessing(batchId, actorCustomerId);
        if (isTerminal(current.status())) {
            return current;
        }

        int processed = 0;
        while (processed < maxItems) {
            try {
                PayrollItemExecutor.ExecutionResult result = itemExecutor.executeNext(batchId, actorCustomerId);
                if (result == null) {
                    break;
                }
                if (!result.attempted()) {
                    break;
                }
                processed++;
            }
            catch (PayrollItemTransferFailure failure) {
                E6FaultInjectionHook hook = e6FaultLab.getIfAvailable();
                if (hook != null) {
                    hook.checkpoint(E6FaultInjectionHook.PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK,
                            java.util.Map.of("batchId", failure.batchId().toString(),
                                    "itemId", failure.itemId().toString(), "failureCode", failure.failureCode()));
                }
                payrollService.markItemFailed(failure.itemId(), failure.failureCode(), failure.getMessage(), actorCustomerId);
                processed++;
            }
        }
        payrollService.finishIfReady(batchId, actorCustomerId);
        return payrollService.get(actorCustomerId, batchId);
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "PARTIALLY_COMPLETED".equals(status) || "FAILED".equals(status);
    }
}
