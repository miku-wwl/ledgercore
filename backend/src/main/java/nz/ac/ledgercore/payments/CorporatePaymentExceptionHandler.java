package nz.ac.ledgercore.payments;

import jakarta.servlet.http.HttpServletRequest;
import nz.ac.ledgercore.api.ApiProblems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "nz.ac.ledgercore.payments")
public class CorporatePaymentExceptionHandler {

    @ExceptionHandler(CorporatePaymentException.class)
    ResponseEntity<ProblemDetail> handle(CorporatePaymentException exception, HttpServletRequest request) {
        ProblemDetail problem = ApiProblems.create(exception.status(), "Corporate payment could not be completed",
                exception.getMessage(), request);
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(exception.status()).body(problem);
    }
}
