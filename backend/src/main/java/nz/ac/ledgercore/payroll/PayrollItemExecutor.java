package nz.ac.ledgercore.payroll;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.transfers.TransferErrorCode;
import nz.ac.ledgercore.transfers.TransferException;
import nz.ac.ledgercore.transfers.TransferReceipt;
import nz.ac.ledgercore.transfers.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollItemExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final TransferService transferService;

    public PayrollItemExecutor(JdbcTemplate jdbcTemplate, TransferService transferService) {
        this.jdbcTemplate = jdbcTemplate;
        this.transferService = transferService;
    }

    /**
     * Claims one available item with SKIP LOCKED and commits its transfer and result together. This method is a
     * single-item transaction; callers must not wrap a batch loop in a transaction.
     */
    @Transactional
    public ExecutionResult executeNext(UUID batchId) {
        Optional<WorkItem> selected = jdbcTemplate.query("""
                SELECT b.id AS batch_id, b.status AS batch_status, b.payer_account_id,
                       b.created_by_customer_id, i.id AS item_id, i.payee_account_id,
                       i.amount_minor, r.status AS item_status, r.transfer_id
                FROM payroll_batches b
                JOIN payroll_items i ON i.batch_id = b.id
                JOIN payroll_item_results r ON r.item_id = i.id
                WHERE b.id = ? AND r.status = 'PENDING'
                ORDER BY i.sequence_no
                LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                """, (rs, row) -> workItem(rs), batchId).stream().findFirst();
        return selected.map(this::executeLocked).orElse(null);
    }

    /** Locks a specific pending item; useful for safe retry and proving duplicate worker behavior. */
    @Transactional
    public ExecutionResult executeOne(UUID batchId, UUID itemId) {
        WorkItem item = jdbcTemplate.query("""
                SELECT b.id AS batch_id, b.status AS batch_status, b.payer_account_id,
                       b.created_by_customer_id, i.id AS item_id, i.payee_account_id,
                       i.amount_minor, r.status AS item_status, r.transfer_id
                FROM payroll_batches b
                JOIN payroll_items i ON i.batch_id = b.id
                JOIN payroll_item_results r ON r.item_id = i.id
                WHERE b.id = ? AND i.id = ?
                FOR UPDATE OF r
                """, (rs, row) -> workItem(rs), batchId, itemId).stream().findFirst()
                .orElseThrow(() -> new PayrollException("PAYROLL_ITEM_NOT_FOUND", "Payroll item was not found.",
                        HttpStatus.NOT_FOUND));
        if (!"PENDING".equals(item.itemStatus())) {
            return new ExecutionResult(item.itemId(), false, item.itemStatus(), item.transferId(), null);
        }
        return executeLocked(item);
    }

    private ExecutionResult executeLocked(WorkItem item) {
        if (!"PROCESSING".equals(item.batchStatus())) {
            throw new PayrollException("BATCH_NOT_PROCESSING", "Payroll items can only execute while the batch is processing.",
                    HttpStatus.CONFLICT);
        }
        String idempotencyKey = "payroll-" + item.batchId() + "-" + item.itemId();
        try {
            TransferReceipt receipt = transferService.transferAndRecord(item.makerCustomerId(), item.payerAccountId(),
                    item.payeeAccountId(), new Money(item.amountMinor()), idempotencyKey,
                    savedReceipt -> recordSuccess(item.itemId(), savedReceipt));
            return new ExecutionResult(item.itemId(), true, "SUCCEEDED", receipt.transferId(), null);
        }
        catch (TransferException exception) {
            if (isPermanentBusinessFailure(exception.code())) {
                throw new PayrollItemTransferFailure(item.batchId(), item.itemId(), exception.code(),
                        exception.getMessage(), exception);
            }
            throw new PayrollException("TRANSFER_EXECUTION_ERROR",
                    "The payroll item remains pending because its transfer could not be safely classified.",
                    HttpStatus.CONFLICT);
        }
    }

    private boolean isPermanentBusinessFailure(TransferErrorCode code) {
        return switch (code) {
            case INSUFFICIENT_FUNDS, ACCOUNT_NOT_FOUND, ACCOUNT_NOT_OPEN, INVALID_REQUEST -> true;
            case UNAUTHENTICATED, IDEMPOTENCY_KEY_REUSED -> false;
        };
    }

    private void recordSuccess(UUID itemId, TransferReceipt receipt) {
        int updated = jdbcTemplate.update("""
                UPDATE payroll_item_results
                SET status = 'SUCCEEDED', attempt_count = attempt_count + 1, transfer_id = ?,
                    completed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE item_id = ? AND status = 'PENDING'
                """, receipt.transferId(), itemId);
        if (updated != 1) {
            throw new IllegalStateException("The payroll result could not be committed with its transfer receipt.");
        }
    }

    private WorkItem workItem(ResultSet rs) throws SQLException {
        return new WorkItem(rs.getObject("batch_id", UUID.class), rs.getString("batch_status"),
                rs.getObject("payer_account_id", UUID.class), rs.getObject("created_by_customer_id", UUID.class),
                rs.getObject("item_id", UUID.class), rs.getObject("payee_account_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("item_status"), rs.getObject("transfer_id", UUID.class));
    }

    public record ExecutionResult(UUID itemId, boolean attempted, String status, UUID transferId, String errorCode) {
    }

    private record WorkItem(UUID batchId, String batchStatus, UUID payerAccountId, UUID makerCustomerId,
            UUID itemId, UUID payeeAccountId, long amountMinor, String itemStatus, UUID transferId) {
    }
}
