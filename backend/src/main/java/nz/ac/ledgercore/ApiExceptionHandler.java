package nz.ac.ledgercore;

import java.net.URI;

import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.TypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import nz.ac.ledgercore.api.ApiNotFoundException;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "Request validation failed",
				"One or more request fields are invalid.", request);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, ServletRequestBindingException.class,
			TypeMismatchException.class, ConstraintViolationException.class })
	ResponseEntity<ProblemDetail> handleMalformedRequest(Exception exception, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", "The request could not be read or bound.", request);
	}

	@ExceptionHandler(ApiNotFoundException.class)
	ResponseEntity<ProblemDetail> handleNotFound(ApiNotFoundException exception, HttpServletRequest request) {
		return problem(HttpStatus.NOT_FOUND, "Not Found", exception.getMessage(), request);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<ProblemDetail> handleInvalidArgument(IllegalArgumentException exception,
			HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", "One or more request values are invalid.", request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
		logger.atError()
			.addKeyValue("event.name", "http.unhandled_failure")
			.addKeyValue("exception.type", exception.getClass().getName())
			.log("Unhandled request failure");
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
