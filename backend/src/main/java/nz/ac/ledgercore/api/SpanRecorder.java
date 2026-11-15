package nz.ac.ledgercore.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Round 4 / E9: retains finished spans locally as structured log records on the {@code ledgercore.trace} logger (one
 * JSON line per span with the console's logstash format). No paid service or collector is involved; an operator can
 * forward the lines to any OTLP-capable pipeline. Attributes must stay low-risk: identifiers of synthetic operations are
 * acceptable in spans, but never credentials, cookies, signatures or beneficiary bank instructions.
 */
public final class SpanRecorder {

    private static final Logger SPANS = LoggerFactory.getLogger("ledgercore.trace");

    private SpanRecorder() {
    }

    public static void record(String name, String kind, TraceContext span, long startNanos, String status,
            Map<String, ?> attributes, List<String> links) {
        long durationMicros = Math.max(0, (System.nanoTime() - startNanos) / 1_000);
        SPANS.atInfo()
                .addKeyValue("event.name", "trace.span")
                .addKeyValue("trace.id", span.traceId())
                .addKeyValue("span.id", span.spanId())
                .addKeyValue("span.parent_id", span.parentSpanId() == null ? "" : span.parentSpanId())
                .addKeyValue("span.name", name)
                .addKeyValue("span.kind", kind)
                .addKeyValue("span.status", status)
                .addKeyValue("span.end", Instant.now().toString())
                .addKeyValue("span.duration_us", durationMicros)
                .addKeyValue("span.links", links == null ? List.of() : links)
                .addKeyValue("span.attributes", attributes == null ? Map.of() : attributes)
                .log("span {} {} {}", kind, name, status);
    }
}
