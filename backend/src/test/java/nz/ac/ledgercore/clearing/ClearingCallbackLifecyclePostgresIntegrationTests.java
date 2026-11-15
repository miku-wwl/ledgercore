package nz.ac.ledgercore.clearing;

import static nz.ac.ledgercore.clearing.ClearingTestFixture.RESERVE;
import static nz.ac.ledgercore.clearing.ClearingTestFixture.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import nz.ac.ledgercore.clearing.ClearingDtos.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Round 4 E5/E8: the signed callback boundary over real HTTP. Authentication covers the exact body bytes; every replay,
 * identity reuse, digest mismatch, sequence collision, gap and contradictory terminal outcome is recorded as an
 * exception without a second financial effect, and the shared key never appears in the application output.
 */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ledgercore.clearing.enabled=true",
        "ledgercore.clearing.shared-secret=" + ClearingCallbackLifecyclePostgresIntegrationTests.SECRET,
        "ledgercore.clearing.simulator-url=http://127.0.0.1:1"
})
class ClearingCallbackLifecyclePostgresIntegrationTests {

    static final String SECRET = "round4-callback-lifecycle-test-secret-32b";

    @LocalServerPort
    int port;

    @Autowired
    ClearingSignature signature;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void signedCallbackLifecycleRejectsTamperingAndRecordsEveryConflictWithoutASecondEffect(CapturedOutput output)
            throws Exception {
        ClearingTestFixture fixture = new ClearingTestFixture(port, jdbc, "R4 callback lifecycle");
        UUID payment = fixture.approvedPayment("9.87", "SIM-SETTLE");
        long reserveBefore = fixture.ledgerMinor(RESERVE);
        Callback settled = fixture.callback(payment, 1, "SETTLED");
        byte[] body = bytes(settled);
        long now = Instant.now().getEpochSecond();

        assertThat(post(body, null, null).statusCode()).isEqualTo(400);
        byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("SETTLED", "REJECTED").getBytes(StandardCharsets.UTF_8);
        assertThat(post(tampered, now, signature.sign(now, body)).statusCode()).isEqualTo(401);
        assertThat(fixture.paymentStatus(payment)).isEqualTo("PENDING_CLEARING/-");

        HttpResponse<String> accepted = post(body, now, signature.sign(now, body));
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).contains("ACCEPTED");
        assertThat(fixture.paymentStatus(payment)).isEqualTo("COMPLETED/SETTLED");
        assertThat(fixture.journals(payment, "settlement")).isEqualTo(1);

        long later = Instant.now().getEpochSecond();
        assertThat(post(body, later, signature.sign(later, body)).body()).contains("DUPLICATE");

        Callback reusedId = new Callback(settled.callbackId(), payment, payment, settled.intentDigest(), 1, "SETTLED",
                settled.simulatorReference(), settled.occurredAt().plusSeconds(1));
        assertThat(signedPost(reusedId).body()).contains("EXCEPTION_RECORDED");
        Callback wrongDigest = new Callback(UUID.randomUUID(), payment, payment, "e".repeat(64), 2, "SETTLED",
                settled.simulatorReference(), Instant.now());
        assertThat(signedPost(wrongDigest).body()).contains("EXCEPTION_RECORDED");
        Callback collidingSequence = new Callback(UUID.randomUUID(), payment, payment, settled.intentDigest(), 1, "SETTLED",
                settled.simulatorReference(), Instant.now().plusSeconds(5));
        assertThat(signedPost(collidingSequence).body()).contains("EXCEPTION_RECORDED");
        Callback gap = new Callback(UUID.randomUUID(), payment, payment, settled.intentDigest(), 3, "SETTLED",
                settled.simulatorReference(), Instant.now());
        assertThat(signedPost(gap).body()).contains("EXCEPTION_RECORDED");
        Callback contradictory = new Callback(UUID.randomUUID(), payment, payment, settled.intentDigest(), 2, "REJECTED",
                settled.simulatorReference(), Instant.now());
        assertThat(signedPost(contradictory).body()).contains("EXCEPTION_RECORDED");

        assertThat(jdbc.queryForList("""
                SELECT category || ':' || severity FROM reconciliation_exceptions WHERE operation_id = ? ORDER BY category
                """, String.class, payment)).containsExactly("CLEARING_CALLBACK_ID_REUSE:CRITICAL",
                "CLEARING_CALLBACK_OUT_OF_ORDER:HIGH", "CLEARING_INTENT_MISMATCH:CRITICAL",
                "CLEARING_SEQUENCE_CONFLICT:CRITICAL", "ILLEGAL_CLEARING_TRANSITION:CRITICAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clearing_callback_inbox WHERE operation_id = ?", Integer.class,
                payment)).isEqualTo(1);
        assertThat(fixture.paymentStatus(payment)).isEqualTo("COMPLETED/SETTLED");
        assertThat(fixture.journals(payment, "settlement")).isEqualTo(1);
        assertThat(fixture.journals(payment, "refund")).isZero();
        assertThat(fixture.ledgerMinor(RESERVE)).isEqualTo(reserveBefore - 987);
        assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain(signature.sign(now, body));
    }

    private HttpResponse<String> signedPost(Callback callback) throws Exception {
        byte[] body = bytes(callback);
        long timestamp = Instant.now().getEpochSecond();
        return post(body, timestamp, signature.sign(timestamp, body));
    }

    private HttpResponse<String> post(byte[] body, Long timestamp, String hmac) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/clearing/callbacks"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (timestamp != null) {
            request.header(ClearingSignature.TIMESTAMP_HEADER, Long.toString(timestamp));
        }
        if (hmac != null) {
            request.header(ClearingSignature.SIGNATURE_HEADER, hmac);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
