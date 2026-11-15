package nz.ac.ledgercore.clearing.simulator;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Round 4 / E9: continues the bank's traceparent on simulator requests and records one SERVER span per request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class SimulatorTraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        SimulatorTrace.Context remote = SimulatorTrace.parse(request.getHeader(SimulatorTrace.HEADER)).orElse(null);
        SimulatorTrace.Context server = remote == null ? SimulatorTrace.root() : remote.child();
        try (SimulatorTrace.AutoCloseableScope scope = server.open()) {
            chain.doFilter(request, response);
        }
        finally {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern == null ? "unmatched" : pattern.toString();
            SimulatorTrace.record(request.getMethod() + " " + route, "SERVER", server, start,
                    response.getStatus() >= 500 ? "ERROR" : "OK", Map.of("http.request.method", request.getMethod(),
                            "http.route", route, "http.response.status_code", response.getStatus(),
                            "trace.remote_parent", remote != null), List.of());
        }
    }
}
