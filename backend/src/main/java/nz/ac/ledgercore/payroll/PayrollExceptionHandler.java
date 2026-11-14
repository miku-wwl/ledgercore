package nz.ac.ledgercore.payroll;

import jakarta.servlet.http.HttpServletRequest;

import nz.ac.ledgercore.api.ApiProblems;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "nz.ac.ledgercore.payroll")
public class PayrollExceptionHandler {

    @ExceptionHandler(PayrollException.class)
    ResponseEntity<ProblemDetail> handlePayroll(PayrollException exception, HttpServletRequest request) {
        ProblemDetail problem = ApiProblems.create(exception.status(), "Payroll request could not be completed",
                exception.getMessage(), request);
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(exception.status()).body(problem);
    }

    @ExceptionHandler(OrganizationAuthorizationException.class)
    ResponseEntity<ProblemDetail> handleForbidden(OrganizationAuthorizationException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiProblems.create(HttpStatus.FORBIDDEN, "Forbidden", exception.getMessage(), request));
    }
}
