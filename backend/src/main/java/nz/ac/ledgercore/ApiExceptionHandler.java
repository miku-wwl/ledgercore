package nz.ac.ledgercore;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "Request validation failed",
				"One or more request fields are invalid.", request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
		logger.error("Unhandled request failure; correlationId={}", correlationId(request), exception);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
				"The request could not be completed.", request);
	}

	private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail,
			HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("about:blank"));
		problem.setTitle(title);
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("correlationId", correlationId(request));
		return ResponseEntity.status(status).body(problem);
	}

	private String correlationId(HttpServletRequest request) {
		Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
		return value instanceof String id ? id : "unavailable";
	}
}
