package nz.ac.ledgercore.identity;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import nz.ac.ledgercore.api.ApiProblems;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "nz.ac.ledgercore.identity")
public class IdentityExceptionHandler {

	@ExceptionHandler(IdentityException.class)
	ResponseEntity<ProblemDetail> handleIdentity(IdentityException exception, HttpServletRequest request) {
		HttpStatus status = switch (exception.code()) {
			case EMAIL_IN_USE -> HttpStatus.CONFLICT;
			case AUTHENTICATION_FAILED -> HttpStatus.UNAUTHORIZED;
			case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
		};
		ProblemDetail problem = ApiProblems.create(status, "Identity request failed", exception.getMessage(), request);
		problem.setProperty("code", exception.code().name());
		return ResponseEntity.status(status).body(problem);
	}
}
