package nz.ac.ledgercore.payroll;

import java.time.Instant;
import java.util.UUID;

public final class PayrollViews {

    private PayrollViews() {
    }

    public record BatchView(UUID id, UUID organizationId, String name, UUID payerAccountId, String status,
            int itemCount, String totalAmount, int pendingCount, int succeededCount, int failedCount,
            String succeededAmount, UUID createdByCustomerId, UUID submittedByCustomerId,
            UUID approvedByCustomerId, UUID rejectedByCustomerId, String rejectionReason,
            String contentDigest, String approvedContentDigest, Instant createdAt, Instant submittedAt,
            Instant approvedAt, Instant rejectedAt, Instant startedAt, Instant completedAt) {
    }

    public record ItemView(UUID id, int sequenceNumber, String employeeReference, String employeeName,
            UUID payeeAccountId, String amount, String status, int attemptCount, UUID transferId,
            String errorCode, String errorMessage, Instant completedAt) {
    }
}
