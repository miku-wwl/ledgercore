package nz.ac.ledgercore.clearing.simulator;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(name = "ledgercore.clearing.callback-dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class SimulatorCallbackDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SimulatorCallbackDispatcher.class);

    private final SimulatorCallbackStore callbackStore;
    private final ClearingSignature signature;
    private final String callbackUrl;
    private final RestClient client;

    public SimulatorCallbackDispatcher(SimulatorCallbackStore callbackStore, ClearingSignature signature,
            @Value("${ledgercore.clearing.bank-callback-url}") String callbackUrl) {
        this.callbackStore = callbackStore;
        this.signature = signature;
        this.callbackUrl = callbackUrl;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(requests).build();
    }

    @Scheduled(fixedDelayString = "${ledgercore.clearing.poll-ms:250}")
    public void dispatch() {
        for (int i = 0; i < 20; i++) {
            CallbackRow row = callbackStore.claimNext();
            if (row == null) {
                return;
            }
            String previousCorrelationId = MDC.get("correlationId");
            MDC.put("correlationId", row.correlationId());
            long spanStart = System.nanoTime();
            SimulatorTrace.Context dispatch = SimulatorTrace.root();
            String status = "ERROR";
            try (SimulatorTrace.AutoCloseableScope scope = dispatch.open()) {
                long timestamp = Instant.now().getEpochSecond();
                byte[] body = row.payload().getBytes(StandardCharsets.UTF_8);
                client.post().uri(callbackUrl).contentType(MediaType.APPLICATION_JSON)
                        .header(ClearingSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                        .header(ClearingSignature.SIGNATURE_HEADER, signature.sign(timestamp, body))
                        .header("X-Correlation-ID", row.correlationId())
                        .header(SimulatorTrace.HEADER, dispatch.traceparent())
                        .body(body).retrieve().toBodilessEntity();
                callbackStore.markDelivered(row.callbackId());
                status = "OK";
                log.info("Simulator callback delivered callbackId={} operationId={}", row.callbackId(), row.operationId());
            }
            catch (RuntimeException exception) {
                callbackStore.markFailed(row, exception.getClass().getSimpleName());
                log.warn("Simulator callback delivery failed callbackId={} operationId={} failureType={}",
                        row.callbackId(), row.operationId(), exception.getClass().getSimpleName());
            }
            finally {
                SimulatorTrace.record("callback.dispatch", "PRODUCER", dispatch, spanStart, status, java.util.Map.of(
                        "messaging.message.id", row.callbackId().toString(), "callback.attempt", row.attemptCount()),
                        row.traceParent() == null ? java.util.List.of() : java.util.List.of(row.traceParent()));
                if (previousCorrelationId == null) {
                    MDC.remove("correlationId");
                }
                else {
                    MDC.put("correlationId", previousCorrelationId);
                }
            }
        }
    }

    public record CallbackRow(UUID callbackId, UUID operationId, int attemptCount, String correlationId,
            String payload, String traceParent) {
    }
}
