package nz.ac.ledgercore;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nz.ac.ledgercore.api.SpanRecorder;
import nz.ac.ledgercore.api.TraceContext;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Round 4 / E9: continues an inbound W3C traceparent (or starts a trace) for every API request and records one SERVER
 * span. The route attribute is the matched pattern, never the raw path, so span names stay low-cardinality. Health
 * probes are not traced. The trace id is correlation data only; authorization never reads it.
 */
public class TraceContextFilter extends OncePerRequestFilter {

    static final String TRACESTATE_MDC_KEY = "tracestate";
    private static final int MAX_TRACESTATE_LENGTH = 512;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        TraceContext remote = TraceContext.parse(request.getHeader(TraceContext.TRACEPARENT_HEADER)).orElse(null);
        TraceContext server = remote == null ? TraceContext.root() : remote.child();
        String traceState = remote == null ? null : request.getHeader(TraceContext.TRACESTATE_HEADER);
        response.setHeader(TraceContext.TRACERESPONSE_HEADER, server.traceparent());
        String previousState = MDC.get(TRACESTATE_MDC_KEY);
        if (traceState != null && traceState.length() <= MAX_TRACESTATE_LENGTH) {
            MDC.put(TRACESTATE_MDC_KEY, traceState);
        }
        try (TraceContext.Scope scope = server.open()) {
            chain.doFilter(request, response);
        }
        finally {
            if (previousState == null) {
                MDC.remove(TRACESTATE_MDC_KEY);
            }
            else {
                MDC.put(TRACESTATE_MDC_KEY, previousState);
            }
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern == null ? "unmatched" : pattern.toString();
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("http.request.method", request.getMethod());
            attributes.put("http.route", route);
            attributes.put("http.response.status_code", response.getStatus());
            attributes.put("trace.remote_parent", remote != null);
            SpanRecorder.record(request.getMethod() + " " + route, "SERVER", server, start,
                    response.getStatus() >= 500 ? "ERROR" : "OK", attributes, List.of());
        }
    }
}
