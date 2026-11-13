package nz.ac.ledgercore.api;

public final class ApiNotFoundException extends RuntimeException {
	public ApiNotFoundException(String message) {
		super(message);
	}
}
