package nz.ac.ledgercore.organizations;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import nz.ac.ledgercore.api.ApiProblems;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class OrganizationExceptionHandler {

    @ExceptionHandler(OrganizationAuthorizationException.class)
    ResponseEntity<ProblemDetail> handleForbidden(OrganizationAuthorizationException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ApiProblems.create(HttpStatus.FORBIDDEN, "Forbidden", exception.getMessage(), request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }
}
