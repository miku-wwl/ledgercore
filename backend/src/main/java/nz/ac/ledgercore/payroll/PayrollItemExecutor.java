package nz.ac.ledgercore.payroll;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;

import nz.ac.ledgercore.events.DurableEventStore;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationService;
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

    /** Terminal result code when the batch creator's debit authority is no longer active at execution time. */
    static final String DEBIT_AUTHORITY_REVOKED = "DEBIT_AUTHORITY_REVOKED";

    private final JdbcTemplate jdbcTemplate;
    private final TransferService transferService;
    private final OrganizationAuthorizationService authorizationService;
    private final DurableEventStore eventStore;

    public PayrollItemExecutor(JdbcTemplate jdbcTemplate, TransferService transferService,
            OrganizationAuthorizationService authorizationService, DurableEventStore eventStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.transferService = transferService;
        this.authorizationService = authorizationService;
        this.eventStore = eventStore;
    }

    /**
     * Claims one available item with SKIP LOCKED and commits its transfer and result together. This method is a
     * single-item transaction; callers must not wrap a batch loop in a transaction.
     */
    @Transactional
    public ExecutionResult executeNext(UUID batchId, UUID releasingActorId) {
        requireCanExecute(batchId, releasingActorId);
        Optional<WorkItem> selected = jdbcTemplate.query("""
                SELECT b.id AS batch_id, b.organization_id, b.status AS batch_status, b.payer_account_id,
                       b.created_by_customer_id, i.id AS item_id, i.payee_account_id,
                       i.amount_minor, r.status AS item_status, r.transfer_id
                FROM payroll_item_results r
                JOIN payroll_items i ON i.id = r.item_id AND i.batch_id = r.batch_id
                JOIN payroll_batches b ON b.id = r.batch_id
                WHERE r.batch_id = ? AND r.status = 'PENDING'
                ORDER BY i.sequence_no
                LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                """, (rs, row) -> workItem(rs), batchId).stream().findFirst();
        return selected.map(item -> executeLocked(item, releasingActorId)).orElse(null);
    }

    /** Locks a specific pending item; useful for safe retry and proving duplicate worker behavior. */
    @Transactional
    public ExecutionResult executeOne(UUID batchId, UUID itemId, UUID releasingActorId) {
        requireCanExecute(batchId, releasingActorId);
        WorkItem item = jdbcTemplate.query("""
                SELECT b.id AS batch_id, b.organization_id, b.status AS batch_status, b.payer_account_id,
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
        return executeLocked(item, releasingActorId);
    }

    private ExecutionResult executeLocked(WorkItem item, UUID releasingActorId) {
        if (!"PROCESSING".equals(item.batchStatus())) {
            throw new PayrollException("BATCH_NOT_PROCESSING", "Payroll items can only execute while the batch is processing.",
                    HttpStatus.CONFLICT);
        }
        // Stable payroll operation identity (unchanged since E2): one key per batch item, scoped to the batch creator.
        String idempotencyKey = TransferService.PAYROLL_IDEMPOTENCY_KEY_PREFIX + item.batchId() + "-" + item.itemId();
        try {
            TransferReceipt receipt = transferService.transferAndRecord(item.makerCustomerId(), item.payerAccountId(),
                    item.payeeAccountId(), new Money(item.amountMinor()), idempotencyKey,
                    savedReceipt -> recordSuccess(item, savedReceipt, releasingActorId));
            return new ExecutionResult(item.itemId(), true, "SUCCEEDED", receipt.transferId(), null);
        }
        catch (TransferException exception) {
            // TransferService reports an unauthorized source as ACCOUNT_NOT_FOUND so the HTTP API never discloses
            // account existence. Payroll accounts are foreign-key protected, so distinguish the authorization
            // outcome explicitly instead of recording a misleading "account not found" result (R2-05).
            if (exception.code() == TransferErrorCode.ACCOUNT_NOT_FOUND
                    && !authorizationService.canDebitAccount(item.makerCustomerId(), item.payerAccountId())) {
                throw new PayrollItemTransferFailure(item.batchId(), item.itemId(), DEBIT_AUTHORITY_REVOKED,
                        "The batch creator no longer holds active PAYROLL_MAKER debit authority for the payer "
                                + "account; no transfer was made.", exception);
            }
            if (isPermanentBusinessFailure(exception.code())) {
                throw new PayrollItemTransferFailure(item.batchId(), item.itemId(), exception.code().name(),
                        exception.getMessage(), exception);
            }
            throw new PayrollException("TRANSFER_EXECUTION_ERROR",
                    "The payroll item remains pending because its transfer could not be safely classified.",
                    HttpStatus.CONFLICT);
        }
    }

    private boolean isPermanentBusinessFailure(TransferErrorCode code) {
        return switch (code) {
            case INSUFFICIENT_FUNDS, ACCOUNT_NOT_FOUND, ACCOUNT_NOT_OPEN, INVALID_REQUEST,
                    CORPORATE_APPROVAL_REQUIRED -> true;
            case UNAUTHENTICATED, IDEMPOTENCY_KEY_REUSED -> false;
        };
    }

    private void recordSuccess(WorkItem item, TransferReceipt receipt, UUID releasingActorId) {
        int updated = jdbcTemplate.update("""
                UPDATE payroll_item_results
                SET status = 'SUCCEEDED', attempt_count = attempt_count + 1, transfer_id = ?,
                    completed_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE item_id = ? AND status = 'PENDING'
                """, receipt.transferId(), item.itemId());
        if (updated != 1) {
            throw new IllegalStateException("The payroll result could not be committed with its transfer receipt.");
        }
        eventStore.recordAudit(releasingActorId, "PAYROLL_ITEM_EXECUTED", "PAYROLL_ITEM", item.itemId(),
                item.organizationId(), Map.of("batchId", item.batchId().toString(),
                "transferId", receipt.transferId().toString()));
        eventStore.enqueue("payroll.item.posted:" + item.itemId(), "payroll.item.posted", "PAYROLL_ITEM",
                item.itemId(), releasingActorId, item.organizationId(), Map.of("batchId", item.batchId().toString(),
                "transferId", receipt.transferId().toString()));
    }

    private void requireCanExecute(UUID batchId, UUID releasingActorId) {
        if (releasingActorId == null) {
            throw new PayrollException("EXECUTOR_REQUIRED", "An authenticated payroll executor is required.",
                    HttpStatus.FORBIDDEN);
        }
        Optional<UUID> organizationId = jdbcTemplate.query("SELECT organization_id FROM payroll_batches WHERE id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), batchId).stream().findFirst();
        if (organizationId.isEmpty()) {
            throw new PayrollException("PAYROLL_BATCH_NOT_FOUND", "Payroll batch was not found.", HttpStatus.NOT_FOUND);
        }
        authorizationService.requireCanExecutePayroll(releasingActorId, organizationId.get());
    }

    private WorkItem workItem(ResultSet rs) throws SQLException {
        return new WorkItem(rs.getObject("batch_id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("batch_status"),
                rs.getObject("payer_account_id", UUID.class), rs.getObject("created_by_customer_id", UUID.class),
                rs.getObject("item_id", UUID.class), rs.getObject("payee_account_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("item_status"), rs.getObject("transfer_id", UUID.class));
    }

    public record ExecutionResult(UUID itemId, boolean attempted, String status, UUID transferId, String errorCode) {
    }

    private record WorkItem(UUID batchId, UUID organizationId, String batchStatus, UUID payerAccountId, UUID makerCustomerId,
            UUID itemId, UUID payeeAccountId, long amountMinor, String itemStatus, UUID transferId) {
    }
}
