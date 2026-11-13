package nz.ac.ledgercore.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import jakarta.servlet.http.HttpServletRequest;

public final class ApiProblems {
	public static final String CORRELATION_ID_ATTRIBUTE = "nz.ac.ledgercore.CorrelationIdFilter.correlationId";

	private ApiProblems() {
	}

	public static ProblemDetail create(HttpStatus status, String title, String detail, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("about:blank"));
		problem.setTitle(title);
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("correlationId", correlationId(request));
		return problem;
	}

	private static String correlationId(HttpServletRequest request) {
		Object value = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
		return value instanceof String id ? id : "unavailable";
	}
}
