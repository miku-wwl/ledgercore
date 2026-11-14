package nz.ac.ledgercore.clearing.simulator;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class SimulatorCorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern ACCEPTED_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Correlation-ID");
        String correlationId = supplied != null && ACCEPTED_ID.matcher(supplied).matches()
                ? supplied : UUID.randomUUID().toString();
        String previousId = MDC.get("correlationId");
        MDC.put("correlationId", correlationId);
        response.setHeader("X-Correlation-ID", correlationId);
        try {
            chain.doFilter(request, response);
        }
        finally {
            if (previousId == null) {
                MDC.remove("correlationId");
            }
            else {
                MDC.put("correlationId", previousId);
            }
        }
    }
}
