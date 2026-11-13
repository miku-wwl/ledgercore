package nz.ac.ledgercore.transfers;

public final class TransferException extends RuntimeException {

	private final TransferErrorCode code;

	public TransferException(TransferErrorCode code, String message) {
		super(message);
		this.code = code;
	}

	public TransferErrorCode code() {
		return code;
	}
}
