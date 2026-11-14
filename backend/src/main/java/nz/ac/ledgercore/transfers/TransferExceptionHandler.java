package nz.ac.ledgercore.transfers;

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
@RestControllerAdvice(basePackages = "nz.ac.ledgercore.transfers")
public class TransferExceptionHandler {

	@ExceptionHandler(TransferException.class)
	ResponseEntity<ProblemDetail> handleTransfer(TransferException exception, HttpServletRequest request) {
		HttpStatus status = switch (exception.code()) {
			case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
			case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
			case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case ACCOUNT_NOT_OPEN, CORPORATE_APPROVAL_REQUIRED, IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
			case INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY;
		};
		ProblemDetail problem = ApiProblems.create(status, "Transfer could not be completed", exception.getMessage(),
				request);
		problem.setProperty("code", exception.code().name());
		return ResponseEntity.status(status).body(problem);
	}
}
