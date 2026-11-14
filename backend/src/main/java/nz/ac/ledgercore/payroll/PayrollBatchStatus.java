package nz.ac.ledgercore.payroll;

public enum PayrollBatchStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    PROCESSING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED
}
