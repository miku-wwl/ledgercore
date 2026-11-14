package nz.ac.ledgercore.clearing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import nz.ac.ledgercore.clearing.ClearingDtos.Callback;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ledgercore.clearing.enabled=true",
        "ledgercore.clearing.shared-secret=round3-test-only-secret-with-32-bytes-minimum",
        "ledgercore.clearing.simulator-url=http://127.0.0.1:1"
})
class ClearingCallbackSecurityIntegrationTests {

    @LocalServerPort
    int port;

    @Autowired
    ClearingSignature signature;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void callbackEndpointRejectsBadAndExpiredMacsButAcceptsSignedUnknownOperationAsException() throws Exception {
        byte[] forged = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long now = Instant.now().getEpochSecond();
        HttpResponse<String> badMac = send(forged, now, "00");
        assertThat(badMac.statusCode()).isEqualTo(401);
        HttpResponse<String> stale = send(forged, now - 600, signature.sign(now - 600, forged));
        assertThat(stale.statusCode()).isEqualTo(401);

        UUID operationId = UUID.randomUUID();
        Callback callback = new Callback(UUID.randomUUID(), operationId, UUID.randomUUID(), "a".repeat(64), 1,
                "SETTLED", "SIM-UNKNOWN-OP", Instant.now());
        byte[] signedBody = JsonMapper.builder().build().writeValueAsBytes(callback);
        HttpResponse<String> acceptedForReview = send(signedBody, now, signature.sign(now, signedBody));
        assertThat(acceptedForReview.statusCode()).isEqualTo(200);
        assertThat(acceptedForReview.body()).contains("EXCEPTION_RECORDED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reconciliation_exceptions
                WHERE operation_id = ? AND category = 'UNKNOWN_CLEARING_OPERATION'
                """, Integer.class, operationId)).isEqualTo(1);
    }

    private HttpResponse<String> send(byte[] body, long timestamp, String hmac) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/clearing/callbacks"))
                .header("Content-Type", "application/json")
                .header(ClearingSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                .header(ClearingSignature.SIGNATURE_HEADER, hmac)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
