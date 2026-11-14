package nz.ac.ledgercore.events;

public interface OutboxEventHandler {

    boolean supports(OutboxEvent event);

    void handle(OutboxEvent event);
}
