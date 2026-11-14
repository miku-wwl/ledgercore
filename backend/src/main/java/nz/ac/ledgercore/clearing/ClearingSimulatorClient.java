package nz.ac.ledgercore.clearing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import nz.ac.ledgercore.clearing.ClearingDtos.InquiryRequest;
import nz.ac.ledgercore.clearing.ClearingDtos.OperationRequest;
import nz.ac.ledgercore.clearing.ClearingDtos.OperationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "ledgercore.clearing.enabled", havingValue = "true")
public class ClearingSimulatorClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ClearingSignature signature;
    private final String simulatorUrl;
    private final RestClient client;
    private final MeterRegistry meterRegistry;

    public ClearingSimulatorClient(ClearingSignature signature,
            @Value("${ledgercore.clearing.simulator-url:http://127.0.0.1:8091}") String simulatorUrl,
            MeterRegistry meterRegistry) {
        this.signature = signature;
        this.simulatorUrl = simulatorUrl.replaceAll("/$", "");
        this.meterRegistry = meterRegistry;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(requests).build();
    }

    public OperationResponse submit(OperationRequest operation) {
        return post("/internal/operations", operation, "submit");
    }

    public OperationResponse inquire(UUID operationId) {
        return post("/internal/operations/" + operationId + "/inquiry", new InquiryRequest(operationId), "inquiry");
    }

    private OperationResponse post(String path, Object request, String operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            byte[] body = JSON.writeValueAsBytes(request);
            long timestamp = Instant.now().getEpochSecond();
            var call = client.post().uri(simulatorUrl + path).contentType(MediaType.APPLICATION_JSON)
                    .header(ClearingSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(ClearingSignature.SIGNATURE_HEADER, signature.sign(timestamp, body));
            String correlationId = MDC.get("correlationId");
            if (correlationId != null) {
                call.header("X-Correlation-ID", correlationId);
            }
            return call.body(body).retrieve().body(OperationResponse.class);
        }
        catch (JacksonException exception) {
            outcome = "failure";
            throw new IllegalStateException("Clearing request could not be serialized.", exception);
        }
        catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        }
        finally {
            sample.stop(meterRegistry.timer("ledgercore.clearing.simulator.request.duration",
                    "operation", operation, "outcome", outcome));
        }
    }
}
