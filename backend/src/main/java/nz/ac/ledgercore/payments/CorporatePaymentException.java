package nz.ac.ledgercore.payments;

import org.springframework.http.HttpStatus;

public final class CorporatePaymentException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public CorporatePaymentException(String code, String message, HttpStatus status) {
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
