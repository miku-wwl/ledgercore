package nz.ac.ledgercore.clearing.simulator;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Round 4 / E9: the independent simulator's own minimal W3C Trace Context and local span log. It is deliberately not
 * shared code with the bank: the two systems agree only on the traceparent wire format. Trace data never authenticates
 * a request; requests and callbacks are authenticated by their HMAC over the body bytes.
 */
final class SimulatorTrace {

    static final String HEADER = "traceparent";
    static final String MDC_TRACEPARENT = "traceparent";
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger SPANS = LoggerFactory.getLogger("ledgercore.simulator.trace");

    private SimulatorTrace() {
    }

    record Context(String traceId, String spanId, String parentSpanId) {

        Context child() {
            return new Context(traceId, hex(8), spanId);
        }

        String traceparent() {
            return "00-" + traceId + "-" + spanId + "-01";
        }

        AutoCloseableScope open() {
            String previous = MDC.get(MDC_TRACEPARENT);
            String previousTrace = MDC.get("traceId");
            MDC.put(MDC_TRACEPARENT, traceparent());
            MDC.put("traceId", traceId);
            return () -> {
                restore(MDC_TRACEPARENT, previous);
                restore("traceId", previousTrace);
            };
        }
    }

    interface AutoCloseableScope extends AutoCloseable {
        @Override
        void close();
    }

    static Optional<Context> parse(String header) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher matcher = TRACEPARENT.matcher(header.trim());
        if (!matcher.matches() || matcher.group(1).chars().allMatch(c -> c == '0')
                || matcher.group(2).chars().allMatch(c -> c == '0')) {
            return Optional.empty();
        }
        return Optional.of(new Context(matcher.group(1), matcher.group(2), null));
    }

    static Context root() {
        return new Context(hex(16), hex(8), null);
    }

    static Optional<String> currentTraceparent() {
        return Optional.ofNullable(MDC.get(MDC_TRACEPARENT));
    }

    static void record(String name, String kind, Context span, long startNanos, String status,
            Map<String, ?> attributes, List<String> links) {
        SPANS.atInfo()
                .addKeyValue("event.name", "trace.span")
                .addKeyValue("trace.id", span.traceId())
                .addKeyValue("span.id", span.spanId())
                .addKeyValue("span.parent_id", span.parentSpanId() == null ? "" : span.parentSpanId())
                .addKeyValue("span.name", name)
                .addKeyValue("span.kind", kind)
                .addKeyValue("span.status", status)
                .addKeyValue("span.end", Instant.now().toString())
                .addKeyValue("span.duration_us", Math.max(0, (System.nanoTime() - startNanos) / 1_000))
                .addKeyValue("span.links", links == null ? List.of() : links)
                .addKeyValue("span.attributes", attributes == null ? Map.of() : attributes)
                .log("span {} {} {}", kind, name, status);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        }
        else {
            MDC.put(key, value);
        }
    }

    private static String hex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        value[0] |= 1;
        return HexFormat.of().formatHex(value);
    }
}
