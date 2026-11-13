package nz.ac.ledgercore.identity;

public final class IdentityException extends RuntimeException {

	private final IdentityErrorCode code;

	public IdentityException(IdentityErrorCode code, String message) {
		super(message);
		this.code = code;
	}

	public IdentityErrorCode code() {
		return code;
	}
}
