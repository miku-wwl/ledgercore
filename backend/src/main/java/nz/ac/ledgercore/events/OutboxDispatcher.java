package nz.ac.ledgercore.events;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** At-least-once dispatcher. Handlers must be idempotent because a crash can follow a successful side effect. */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final DurableEventStore eventStore;
    private final List<OutboxEventHandler> handlers;
    private final MeterRegistry meterRegistry;

    @Autowired
    public OutboxDispatcher(DurableEventStore eventStore, List<OutboxEventHandler> handlers, MeterRegistry meterRegistry) {
        this.eventStore = eventStore;
        this.handlers = handlers;
        this.meterRegistry = meterRegistry;
    }

    public OutboxDispatcher(DurableEventStore eventStore, List<OutboxEventHandler> handlers) {
        this(eventStore, handlers, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    public boolean dispatchOne() {
        OutboxEvent event = eventStore.claimNext();
        if (event == null) {
            return false;
        }
        dispatchClaimed(event);
        return true;
    }

    void dispatchClaimed(OutboxEvent event) {
        String previousCorrelationId = MDC.get("correlationId");
        if (event.correlationId() == null) {
            MDC.remove("correlationId");
        }
        else {
            MDC.put("correlationId", event.correlationId());
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        long spanStart = System.nanoTime();
        nz.ac.ledgercore.api.TraceContext consumer = nz.ac.ledgercore.api.TraceContext.root();
        nz.ac.ledgercore.api.TraceContext.Scope traceScope = consumer.open();
        try {
            OutboxEventHandler handler = handlers.stream().filter(candidate -> candidate.supports(event))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No handler accepts the event type."));
            handler.handle(event);
            eventStore.markDelivered(event.id());
            log.info("Outbox event delivered type={} id={}", event.eventType(), event.id());
        }
        catch (RuntimeException exception) {
            outcome = "failure";
            meterRegistry.counter("ledgercore.outbox.delivery.failures").increment();
            eventStore.markDeliveryFailure(event.id(), event.attemptCount(), exception.getClass().getSimpleName());
            log.warn("Outbox event delivery failed type={} id={} failureType={}", event.eventType(), event.id(),
                    exception.getClass().getSimpleName());
        }
        finally {
            traceScope.close();
            nz.ac.ledgercore.api.SpanRecorder.record("outbox.dispatch " + event.eventType(), "CONSUMER", consumer,
                    spanStart, "success".equals(outcome) ? "OK" : "ERROR", java.util.Map.of(
                            "messaging.message.id", event.id().toString(), "outbox.event_type", event.eventType(),
                            "outbox.attempt", event.attemptCount(), "outbox.outcome", outcome),
                    event.traceParent() == null ? java.util.List.of() : java.util.List.of(event.traceParent()));
            sample.stop(meterRegistry.timer("ledgercore.outbox.delivery.duration", "outcome", outcome));
            if (previousCorrelationId == null) {
                MDC.remove("correlationId");
            }
            else {
                MDC.put("correlationId", previousCorrelationId);
            }
        }
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
