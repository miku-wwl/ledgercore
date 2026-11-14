package nz.ac.ledgercore.payroll;

import org.springframework.http.HttpStatus;

public final class PayrollException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public PayrollException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
