package nz.ac.ledgercore.clearing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import nz.ac.ledgercore.api.E6FaultInjectionHook;
import nz.ac.ledgercore.ledger.LedgerWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the one local journal that a persisted, authenticated terminal clearing outcome requires. The decision is
 * derived only from the locked payment row, so every caller (after-commit fast path, durable outbox handler, governed
 * retry) is idempotent: a second call finds the journal already linked and does nothing.
 */
@Service
public class ClearingFinancialEffectProcessor {

    private final JdbcTemplate jdbc;
    private final LedgerWriter ledgerWriter;
    private final ObjectProvider<E6FaultInjectionHook> faultLab;

    public ClearingFinancialEffectProcessor(JdbcTemplate jdbc, LedgerWriter ledgerWriter,
            ObjectProvider<E6FaultInjectionHook> faultLab) {
        this.jdbc = jdbc;
        this.ledgerWriter = ledgerWriter;
        this.faultLab = faultLab;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EffectResult apply(UUID paymentId) {
        List<EffectState> states = jdbc.query("""
                SELECT status, external_status, settlement_journal_entry_id, reversal_journal_entry_id
                FROM corporate_payment_intents WHERE id = ? FOR UPDATE
                """, (rs, row) -> new EffectState(rs.getString("status"), rs.getString("external_status"),
                    rs.getObject("settlement_journal_entry_id", UUID.class),
                    rs.getObject("reversal_journal_entry_id", UUID.class)), paymentId);
        if (states.isEmpty()) {
            return new EffectResult(paymentId, Effect.NONE, Outcome.PAYMENT_NOT_FOUND, null);
        }
        EffectState state = states.getFirst();
        boolean open = List.of("PENDING_CLEARING", "UNKNOWN").contains(state.status());
        if ("SETTLED".equals(state.externalStatus())) {
            if (state.settlementEntryId() != null) {
                return new EffectResult(paymentId, Effect.SETTLEMENT, Outcome.ALREADY_APPLIED, state.settlementEntryId());
            }
            if (open) {
                UUID journalEntryId = ledgerWriter.settleClearingPayment(paymentId);
                checkpointBeforeCommit(paymentId, Effect.SETTLEMENT, journalEntryId);
                return new EffectResult(paymentId, Effect.SETTLEMENT, Outcome.APPLIED, journalEntryId);
            }
        }
        else if ("REJECTED".equals(state.externalStatus())) {
            if (state.reversalEntryId() != null) {
                return new EffectResult(paymentId, Effect.REFUND, Outcome.ALREADY_APPLIED, state.reversalEntryId());
            }
            if (open && state.settlementEntryId() == null) {
                UUID journalEntryId = ledgerWriter.refundRejectedCorporatePayment(paymentId);
                checkpointBeforeCommit(paymentId, Effect.REFUND, journalEntryId);
                return new EffectResult(paymentId, Effect.REFUND, Outcome.APPLIED, journalEntryId);
            }
        }
        return new EffectResult(paymentId, Effect.NONE, Outcome.NOT_REQUIRED, null);
    }

    private void checkpointBeforeCommit(UUID paymentId, Effect effect, UUID journalEntryId) {
        E6FaultInjectionHook hook = faultLab.getIfAvailable();
        if (hook != null) {
            hook.checkpoint(E6FaultInjectionHook.CLEARING_EFFECT_POSTED_BEFORE_COMMIT, Map.of(
                    "paymentId", paymentId.toString(), "effect", effect.name(), "journalEntryId", journalEntryId.toString()));
        }
    }

    public enum Effect {
        NONE, SETTLEMENT, REFUND
    }

    public enum Outcome {
        APPLIED, ALREADY_APPLIED, NOT_REQUIRED, PAYMENT_NOT_FOUND
    }

    public record EffectResult(UUID paymentId, Effect effect, Outcome outcome, UUID journalEntryId) {
    }

    private record EffectState(String status, String externalStatus, UUID settlementEntryId, UUID reversalEntryId) {
    }
}
