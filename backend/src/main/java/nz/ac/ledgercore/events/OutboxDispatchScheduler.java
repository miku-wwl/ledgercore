package nz.ac.ledgercore.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgercore.outbox.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatchScheduler {

    private final OutboxDispatcher dispatcher;

    public OutboxDispatchScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${ledgercore.outbox.poll-ms:1000}")
    public void dispatchPendingEvents() {
        dispatcher.dispatchAvailable(20);
    }
}
