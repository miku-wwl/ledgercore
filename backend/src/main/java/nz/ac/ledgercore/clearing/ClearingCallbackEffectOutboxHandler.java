package nz.ac.ledgercore.clearing;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import nz.ac.ledgercore.events.DurableEventStore;
import nz.ac.ledgercore.events.OutboxEvent;
import nz.ac.ledgercore.events.OutboxEventHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Round 4 (R4-01): durable owner of the financial effect of an accepted clearing callback. The event is committed in the
 * same transaction as the callback inbox row and the payment's external status, so a crash, a failed after-commit fast
 * path or a terminated database session leaves this work item behind. It is processed with the outbox's lease,
 * SKIP LOCKED claim, backoff and dead-letter limit. Deliberately not conditional on the clearing endpoint being enabled:
 * a node restarted without the simulator integration must still complete effects it has already accepted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 110)
public class ClearingCallbackEffectOutboxHandler implements OutboxEventHandler {

    /** Written by the callback transaction for every accepted callback. */
    public static final String CALLBACK_ACCEPTED = "clearing.callback.accepted";
    /** Written by migration V12 for terminal outcomes that a pre-Round-4 consumer acknowledged without their effect. */
    public static final String EFFECT_BACKFILL = "clearing.financial-effect.backfill";
    static final String CONSUMER_KEY = "clearing-financial-effect-v1";
    private static final Set<String> TYPES = Set.of(CALLBACK_ACCEPTED, EFFECT_BACKFILL);

    private final JdbcTemplate jdbc;
    private final ClearingFinancialEffects effects;
    private final DurableEventStore eventStore;

    public ClearingCallbackEffectOutboxHandler(JdbcTemplate jdbc, ClearingFinancialEffects effects,
            DurableEventStore eventStore) {
        this.jdbc = jdbc;
        this.effects = effects;
        this.eventStore = eventStore;
    }

    @Override
    public boolean supports(OutboxEvent event) {
        return TYPES.contains(event.eventType());
    }

    @Override
    public void handle(OutboxEvent event) {
        List<UUID> payments = jdbc.query("SELECT id FROM corporate_payment_intents WHERE operation_id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), event.aggregateId());
        if (payments.isEmpty()) {
            throw new IllegalStateException("A clearing effect work item has no persisted payment intent.");
        }
        effects.applyOwedEffect(payments.getFirst(), "outbox");
        eventStore.recordConsumerReceipt(event, CONSUMER_KEY);
    }
}
