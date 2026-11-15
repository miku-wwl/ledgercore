package nz.ac.ledgercore.api;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * Round 4 / E9: minimal W3C Trace Context (traceparent version 00). It is used only to correlate spans across the bank,
 * its durable work and the independent simulator. A trace id received from a client or a peer is never an
 * authorization input: it selects no data, grants no access and is not part of any signed message.
 */
public record TraceContext(String traceId, String spanId, String parentSpanId, boolean sampled) {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACESTATE_HEADER = "tracestate";
    /** W3C Trace Context Level 2 response header: lets a caller find the server span it produced. */
    public static final String TRACERESPONSE_HEADER = "traceresponse";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_TRACEPARENT = "traceparent";
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Parses an inbound traceparent; an invalid, unknown-version or all-zero value is ignored (a new trace starts). */
    public static Optional<TraceContext> parse(String header) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher matcher = TRACEPARENT.matcher(header.trim());
        if (!matcher.matches() || matcher.group(1).chars().allMatch(c -> c == '0')
                || matcher.group(2).chars().allMatch(c -> c == '0')) {
            return Optional.empty();
        }
        boolean sampled = (Integer.parseInt(matcher.group(3), 16) & 1) == 1;
        return Optional.of(new TraceContext(matcher.group(1), matcher.group(2), null, sampled));
    }

    public static TraceContext root() {
        return new TraceContext(randomHex(16), randomHex(8), null, true);
    }

    /** The context active on this thread, if a filter, dispatcher or worker opened one. */
    public static Optional<TraceContext> current() {
        String traceparent = MDC.get(MDC_TRACEPARENT);
        return traceparent == null ? Optional.empty() : parse(traceparent);
    }

    public TraceContext child() {
        return new TraceContext(traceId, randomHex(8), spanId, sampled);
    }

    public String traceparent() {
        return "00-" + traceId + "-" + spanId + "-" + (sampled ? "01" : "00");
    }

    /** Makes this context current for log correlation; closing restores the previous one. */
    public Scope open() {
        String previousTrace = MDC.get(MDC_TRACE_ID);
        String previousSpan = MDC.get(MDC_SPAN_ID);
        String previousParent = MDC.get(MDC_TRACEPARENT);
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_SPAN_ID, spanId);
        MDC.put(MDC_TRACEPARENT, traceparent());
        return () -> {
            restore(MDC_TRACE_ID, previousTrace);
            restore(MDC_SPAN_ID, previousSpan);
            restore(MDC_TRACEPARENT, previousParent);
        };
    }

    private static void restore(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        }
        else {
            MDC.put(key, value);
        }
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        do {
            RANDOM.nextBytes(value);
        } while (allZero(value));
        return HexFormat.of().formatHex(value);
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
