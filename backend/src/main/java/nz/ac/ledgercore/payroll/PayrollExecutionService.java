package nz.ac.ledgercore.payroll;

import java.util.UUID;

import nz.ac.ledgercore.api.E6FaultInjectionHook;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class PayrollExecutionService {

    private static final int MAX_ITEMS_PER_REQUEST = 1_000;

    private final PayrollService payrollService;
    private final OrganizationAuthorizationService authorizationService;
    private final PayrollItemExecutor itemExecutor;
    private final ObjectProvider<E6FaultInjectionHook> e6FaultLab;
    private final MeterRegistry meterRegistry;

    public PayrollExecutionService(PayrollService payrollService,
            OrganizationAuthorizationService authorizationService, PayrollItemExecutor itemExecutor,
            ObjectProvider<E6FaultInjectionHook> e6FaultLab, MeterRegistry meterRegistry) {
        this.payrollService = payrollService;
        this.authorizationService = authorizationService;
        this.itemExecutor = itemExecutor;
        this.e6FaultLab = e6FaultLab;
        this.meterRegistry = meterRegistry;
    }

    /** The orchestration method is deliberately non-transactional: each executor call commits one item. */
    public PayrollViews.BatchView execute(UUID actorCustomerId, UUID batchId, int maxItems) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            return executeBatch(actorCustomerId, batchId, maxItems);
        }
        catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        }
        finally {
            sample.stop(meterRegistry.timer("ledgercore.payroll.batch.duration", "outcome", outcome));
        }
    }

    private PayrollViews.BatchView executeBatch(UUID actorCustomerId, UUID batchId, int maxItems) {
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
            Timer.Sample itemSample = Timer.start(meterRegistry);
            String sampleKind = "item";
            String itemOutcome = "success";
            try {
                PayrollItemExecutor.ExecutionResult result = itemExecutor.executeNext(batchId, actorCustomerId);
                if (result == null) {
                    sampleKind = "claim";
                    itemOutcome = "empty";
                    break;
                }
                if (!result.attempted()) {
                    sampleKind = "claim";
                    itemOutcome = "not_attempted";
                    break;
                }
                itemOutcome = "SUCCEEDED".equals(result.status()) ? "success" : "business_failure";
                processed++;
            }
            catch (PayrollItemTransferFailure failure) {
                itemOutcome = "business_failure";
                E6FaultInjectionHook hook = e6FaultLab.getIfAvailable();
                if (hook != null) {
                    hook.checkpoint(E6FaultInjectionHook.PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK,
                            java.util.Map.of("batchId", failure.batchId().toString(),
                                    "itemId", failure.itemId().toString(), "failureCode", failure.failureCode()));
                }
                payrollService.markItemFailed(failure.itemId(), failure.failureCode(), failure.getMessage(), actorCustomerId);
                processed++;
            }
            catch (RuntimeException exception) {
                itemOutcome = "failure";
                throw exception;
            }
            finally {
                itemSample.stop(meterRegistry.timer("ledgercore.payroll." + sampleKind + ".duration",
                        "outcome", itemOutcome));
            }
        }
        payrollService.finishIfReady(batchId, actorCustomerId);
        return payrollService.get(actorCustomerId, batchId);
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "PARTIALLY_COMPLETED".equals(status) || "FAILED".equals(status);
    }
}
