package nz.ac.ledgercore.events;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Default idempotent consumer for committed business facts not routed to an external boundary. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DurableOutboxConsumer implements OutboxEventHandler {

    private final DurableEventStore eventStore;

    public DurableOutboxConsumer(DurableEventStore eventStore) {
        this.eventStore = eventStore;
    }

    @Override
    public boolean supports(OutboxEvent event) {
        return true;
    }

    @Override
    public void handle(OutboxEvent event) {
        eventStore.recordConsumerReceipt(event, "durable-fact-observer-v1");
    }
}
