package nz.ac.ledgercore.clearing.simulator;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
            try {
                long timestamp = Instant.now().getEpochSecond();
                byte[] body = row.payload().getBytes(StandardCharsets.UTF_8);
                client.post().uri(callbackUrl).contentType(MediaType.APPLICATION_JSON)
                        .header(ClearingSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                        .header(ClearingSignature.SIGNATURE_HEADER, signature.sign(timestamp, body))
                        .body(body).retrieve().toBodilessEntity();
                callbackStore.markDelivered(row.callbackId());
            }
            catch (RuntimeException exception) {
                callbackStore.markFailed(row, exception.getClass().getSimpleName());
            }
        }
    }

    public record CallbackRow(UUID callbackId, UUID operationId, int attemptCount, String payload) {
    }
}
