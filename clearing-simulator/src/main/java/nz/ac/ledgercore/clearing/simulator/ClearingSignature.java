package nz.ac.ledgercore.clearing.simulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClearingSignature {

    public static final String TIMESTAMP_HEADER = "X-Clearing-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Clearing-Signature";

    private final byte[] secret;
    private final long skewSeconds;

    public ClearingSignature(@Value("${ledgercore.clearing.shared-secret}") String secret,
            @Value("${ledgercore.clearing.timestamp-skew-seconds:300}") long skewSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.skewSeconds = skewSeconds;
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("The clearing HMAC secret must contain at least 32 UTF-8 bytes.");
        }
        if (skewSeconds < 1 || skewSeconds > 900) {
            throw new IllegalArgumentException("The clearing timestamp skew must be between 1 and 900 seconds.");
        }
    }

    public String sign(long epochSeconds, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(Long.toString(epochSeconds).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(rawBody);
            return HexFormat.of().formatHex(mac.doFinal());
        }
        catch (Exception exception) {
            throw new IllegalStateException("Clearing signature could not be created.", exception);
        }
    }

    public boolean verify(String timestampHeader, String signatureHeader, byte[] rawBody) {
        if (timestampHeader == null || signatureHeader == null || rawBody == null || rawBody.length > 16_384) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(timestampHeader);
            if (Duration.between(Instant.ofEpochSecond(timestamp), Instant.now()).abs().getSeconds() > skewSeconds) {
                return false;
            }
            byte[] supplied = HexFormat.of().parseHex(signatureHeader);
            byte[] expected = HexFormat.of().parseHex(sign(timestamp, rawBody));
            return MessageDigest.isEqual(expected, supplied);
        }
        catch (RuntimeException exception) {
            return false;
        }
    }
}
