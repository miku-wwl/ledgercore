package nz.ac.ledgercore.events;

import java.util.List;

import org.springframework.stereotype.Service;

/** At-least-once dispatcher. Handlers must be idempotent because a crash can follow a successful side effect. */
@Service
public class OutboxDispatcher {

    private final DurableEventStore eventStore;
    private final List<OutboxEventHandler> handlers;

    public OutboxDispatcher(DurableEventStore eventStore, List<OutboxEventHandler> handlers) {
        this.eventStore = eventStore;
        this.handlers = handlers;
    }

    public boolean dispatchOne() {
        OutboxEvent event = eventStore.claimNext();
        if (event == null) {
            return false;
        }
        try {
            OutboxEventHandler handler = handlers.stream().filter(candidate -> candidate.supports(event))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No handler accepts the event type."));
            handler.handle(event);
            eventStore.markDelivered(event.id());
        }
        catch (RuntimeException exception) {
            eventStore.markDeliveryFailure(event.id(), event.attemptCount(), exception.getMessage());
        }
        return true;
    }

    public int dispatchAvailable(int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        int handled = 0;
        while (handled < safeLimit && dispatchOne()) {
            handled++;
        }
        return handled;
    }
}
