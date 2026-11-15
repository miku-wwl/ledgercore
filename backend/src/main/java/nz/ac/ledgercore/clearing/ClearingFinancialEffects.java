package nz.ac.ledgercore.clearing;

import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import nz.ac.ledgercore.clearing.ClearingFinancialEffectProcessor.EffectResult;
import nz.ac.ledgercore.clearing.ClearingFinancialEffectProcessor.Outcome;
import nz.ac.ledgercore.ledger.InsufficientLedgerBalanceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Round 4 (R4-01): the single owner of the clearing financial effect. The durable outbox event committed with the
 * callback is the work item; this service applies it and classifies failures. It never posts a compensating journal
 * and never treats a transport result, an outbox delivery or an UNKNOWN outcome as a reason to refund.
 */
@Service
public class ClearingFinancialEffects {

    private static final Logger log = LoggerFactory.getLogger(ClearingFinancialEffects.class);

    private final ClearingFinancialEffectProcessor processor;
    private final ClearingExceptionRecorder exceptions;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;

    public ClearingFinancialEffects(ClearingFinancialEffectProcessor processor, ClearingExceptionRecorder exceptions,
            JdbcTemplate jdbc, MeterRegistry meterRegistry) {
        this.processor = processor;
        this.exceptions = exceptions;
        this.jdbc = jdbc;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Applies the effect owed by the persisted outcome. A failure is rethrown so that the durable caller keeps the work
     * item, retries it with backoff and escalates it to a dead letter after its attempt limit.
     */
    public EffectResult applyOwedEffect(UUID paymentId, String path) {
        long spanStart = System.nanoTime();
        nz.ac.ledgercore.api.TraceContext span = nz.ac.ledgercore.api.TraceContext.current()
                .map(nz.ac.ledgercore.api.TraceContext::child).orElseGet(nz.ac.ledgercore.api.TraceContext::root);
        String status = "ERROR";
        String detail = "failure";
        try (nz.ac.ledgercore.api.TraceContext.Scope scope = span.open()) {
            EffectResult result = processor.apply(paymentId);
            status = "OK";
            detail = result.outcome().name();
            if (result.outcome() == Outcome.APPLIED) {
                meterRegistry.counter("ledgercore.clearing.effect.applied", "effect", result.effect().name(), "path", path)
                        .increment();
                log.info("Clearing financial effect applied effect={} paymentId={} journalEntryId={} path={}",
                        result.effect(), paymentId, result.journalEntryId(), path);
            }
            else if (result.outcome() == Outcome.ALREADY_APPLIED) {
                meterRegistry.counter("ledgercore.clearing.effect.duplicates", "path", path).increment();
            }
            return result;
        }
        catch (InsufficientLedgerBalanceException exception) {
            meterRegistry.counter("ledgercore.clearing.effect.failures", "path", path, "reason", "reserve_insufficient")
                    .increment();
            recordReserveShortage(paymentId);
            detail = "RESERVE_INSUFFICIENT";
            throw new ClearingEffectDeferredException("The simulation reserve cannot cover the settlement yet.", exception);
        }
        catch (RuntimeException exception) {
            meterRegistry.counter("ledgercore.clearing.effect.failures", "path", path,
                    "reason", exception.getClass().getSimpleName()).increment();
            throw exception;
        }
        finally {
            nz.ac.ledgercore.api.SpanRecorder.record("clearing.financial_effect", "INTERNAL", span, spanStart, status,
                    java.util.Map.of("payment.id", paymentId.toString(), "effect.path", path, "effect.outcome", detail),
                    java.util.List.of());
        }
    }

    /** After-commit fast path. It never throws: the committed outbox event remains the durable owner of the effect. */
    public void tryApplyNow(UUID paymentId) {
        try {
            applyOwedEffect(paymentId, "after_commit");
        }
        catch (RuntimeException exception) {
            log.warn("Clearing financial effect deferred to its durable outbox event paymentId={} failureType={}",
                    paymentId, exception.getClass().getSimpleName());
        }
    }

    private void recordReserveShortage(UUID paymentId) {
        List<PaymentKeys> keys = jdbc.query("SELECT operation_id, organization_id FROM corporate_payment_intents WHERE id = ?",
                (rs, row) -> new PaymentKeys(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), paymentId);
        if (!keys.isEmpty()) {
            exceptions.recordReserveInsufficient(paymentId, keys.getFirst().operationId(), keys.getFirst().organizationId());
        }
    }

    private record PaymentKeys(UUID operationId, UUID organizationId) {
    }
}
