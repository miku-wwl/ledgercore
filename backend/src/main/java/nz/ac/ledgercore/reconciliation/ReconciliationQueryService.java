package nz.ac.ledgercore.reconciliation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class ReconciliationQueryService {

	private static final UUID CLEARING_PAYABLE_LEDGER = UUID.fromString("00000000-0000-5000-8000-000000000002");
	private static final UUID SIMULATION_RESERVE_LEDGER = UUID.fromString("00000000-0000-5000-8000-000000000001");

	private final JdbcTemplate jdbc;

	ReconciliationQueryService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	List<ReconciliationFinding> inspect(UUID organizationId) {
		List<ReconciliationFinding> findings = new ArrayList<>();
		findings.addAll(projectionMismatches(organizationId));
		findings.addAll(transferMismatches(organizationId));
		findings.addAll(payrollItemMismatches(organizationId));
		findings.addAll(payrollBatchMismatches(organizationId));
		findings.addAll(paymentMismatches(organizationId));
		findings.addAll(missingBusinessEvents(organizationId));
		findings.addAll(outboxOperationalFindings(organizationId));
		findings.addAll(organizationOwnershipMismatches(organizationId));
		return List.copyOf(findings);
	}

	List<ClearingOperationSnapshot> clearingOperations(UUID organizationId, int limit) {
		return jdbc.query("""
				SELECT p.id AS payment_id, p.operation_id, p.intent_digest, p.status AS payment_status,
				       p.external_status, c.status AS instruction_status, c.intent_digest AS instruction_digest,
				       c.simulator_reference, callback.sequence_no AS callback_sequence,
				       callback.external_status AS callback_status
				FROM corporate_payment_intents p
				JOIN clearing_instructions c ON c.operation_id = p.operation_id
				LEFT JOIN LATERAL (
				    SELECT i.sequence_no, i.external_status FROM clearing_callback_inbox i
				    WHERE i.operation_id = p.operation_id ORDER BY i.sequence_no DESC LIMIT 1
				) callback ON TRUE
				WHERE p.organization_id = ?
				ORDER BY p.updated_at DESC, p.id DESC
				LIMIT ?
				""", (rs, row) -> new ClearingOperationSnapshot(rs.getObject("payment_id", UUID.class),
				rs.getObject("operation_id", UUID.class), rs.getString("intent_digest"),
				rs.getString("payment_status"), rs.getString("external_status"),
				rs.getString("instruction_status"), rs.getString("instruction_digest"),
				rs.getString("simulator_reference"), nullableLong(rs, "callback_sequence"),
				rs.getString("callback_status")), organizationId, limit);
	}

	long countClearingOperations(UUID organizationId) {
		Long count = jdbc.queryForObject("""
				SELECT count(*) FROM clearing_instructions c
				JOIN corporate_payment_intents p ON p.operation_id = c.operation_id
				WHERE p.organization_id = ?
				""", Long.class, organizationId);
		return count == null ? 0 : count;
	}

	private List<ReconciliationFinding> projectionMismatches(UUID organizationId) {
		return jdbc.query("""
				WITH ledger_totals AS (
				    SELECT la.id AS ledger_account_id, la.customer_account_id, la.normal_side, la.currency,
				           COALESCE(sum(CASE WHEN p.side = la.normal_side
				                            THEN p.amount_minor ELSE -p.amount_minor END), 0) AS ledger_minor
				    FROM ledger_accounts la
				    LEFT JOIN postings p ON p.ledger_account_id = la.id
				    WHERE la.kind = 'CUSTOMER_DEPOSIT'
				    GROUP BY la.id, la.customer_account_id, la.normal_side, la.currency
				)
				SELECT a.id AS account_id, l.ledger_account_id, l.currency, l.ledger_minor,
				       p.balance_minor AS projected_minor
				FROM accounts a
				LEFT JOIN ledger_totals l ON l.customer_account_id = a.id
				LEFT JOIN account_balance_projection p ON p.ledger_account_id = l.ledger_account_id
				WHERE a.owner_organization_id = ?
				  AND (l.ledger_account_id IS NULL OR p.ledger_account_id IS NULL
				       OR l.ledger_minor <> p.balance_minor)
				ORDER BY a.id
				""", (rs, row) -> {
			UUID accountId = rs.getObject("account_id", UUID.class);
			UUID ledgerId = rs.getObject("ledger_account_id", UUID.class);
			Long ledger = nullableLong(rs, "ledger_minor");
			Long projection = nullableLong(rs, "projected_minor");
			Map<String, Object> expected = facts("resourceType", "ACCOUNT", "resourceId", accountId.toString(),
					"ledgerAccountId", ledgerId == null ? null : ledgerId.toString(), "ledgerMinor", ledger);
			Map<String, Object> observed = facts("projectionMinor", projection,
					"projectionPresent", projection != null);
			return finding("LEDGER_PROJECTION_MISMATCH", "HIGH", "ACCOUNT", accountId, null, expected, observed);
		}, organizationId);
	}

	private List<ReconciliationFinding> transferMismatches(UUID organizationId) {
		return jdbc.query("""
				SELECT t.id AS transfer_id, t.source_account_id, t.target_account_id, t.amount_minor,
				       t.currency, je.entry_type, je.currency AS journal_currency,
				       count(p.id) AS posting_count,
				       count(p.id) FILTER (WHERE p.ledger_account_id = src.id AND p.side = 'DEBIT'
				                               AND p.amount_minor = t.amount_minor) AS source_debit_count,
				       count(p.id) FILTER (WHERE p.ledger_account_id = dst.id AND p.side = 'CREDIT'
				                               AND p.amount_minor = t.amount_minor) AS target_credit_count,
				       receipts.receipt_count
				FROM transfers t
				LEFT JOIN journal_entries je ON je.id = t.journal_entry_id
				LEFT JOIN postings p ON p.journal_entry_id = t.journal_entry_id
				LEFT JOIN ledger_accounts src ON src.customer_account_id = t.source_account_id
				    AND src.kind = 'CUSTOMER_DEPOSIT'
				LEFT JOIN ledger_accounts dst ON dst.customer_account_id = t.target_account_id
				    AND dst.kind = 'CUSTOMER_DEPOSIT'
				LEFT JOIN LATERAL (
				    SELECT count(*) AS receipt_count FROM idempotency_records r
				    WHERE r.transfer_id = t.id AND r.operation = 'TRANSFER' AND r.status = 'SUCCEEDED'
				) receipts ON TRUE
				WHERE t.source_organization_id = ?
				GROUP BY t.id, je.entry_type, je.currency, src.id, dst.id, receipts.receipt_count
				HAVING je.entry_type IS DISTINCT FROM 'TRANSFER'
				    OR je.currency IS DISTINCT FROM t.currency
				    OR count(p.id) <> 2
				    OR count(p.id) FILTER (WHERE p.ledger_account_id = src.id AND p.side = 'DEBIT'
				                              AND p.amount_minor = t.amount_minor) <> 1
				    OR count(p.id) FILTER (WHERE p.ledger_account_id = dst.id AND p.side = 'CREDIT'
				                              AND p.amount_minor = t.amount_minor) <> 1
				    OR receipts.receipt_count <> 1
				ORDER BY t.id
				""", (rs, row) -> {
			UUID transferId = rs.getObject("transfer_id", UUID.class);
			return finding("TRANSFER_JOURNAL_RECEIPT_MISMATCH", "CRITICAL", "TRANSFER", transferId,
					transferId, facts("entryType", "TRANSFER", "amountMinor", rs.getLong("amount_minor"),
							"postingCount", 2, "successfulReceiptCount", 1),
					facts("entryType", rs.getString("entry_type"), "journalCurrency", rs.getString("journal_currency"),
							"postingCount", rs.getLong("posting_count"),
							"sourceDebitCount", rs.getLong("source_debit_count"),
							"targetCreditCount", rs.getLong("target_credit_count"),
							"successfulReceiptCount", rs.getLong("receipt_count")));
		}, organizationId);
	}

	private List<ReconciliationFinding> payrollItemMismatches(UUID organizationId) {
		return jdbc.query("""
				SELECT i.id AS item_id, b.id AS batch_id, b.status AS batch_status, b.payer_account_id,
				       b.created_by_customer_id, i.payee_account_id, i.amount_minor, i.currency,
				       r.status AS result_status, r.transfer_id AS result_transfer_id,
				       t.id AS actual_transfer_id, t.source_account_id, t.source_organization_id,
				       t.owner_customer_id, t.target_account_id, t.amount_minor AS transfer_amount_minor,
				       t.currency AS transfer_currency
			FROM payroll_batches b
			JOIN payroll_items i ON i.batch_id = b.id
			LEFT JOIN payroll_item_results r ON r.item_id = i.id
			LEFT JOIN transfers t ON t.id = r.transfer_id
			WHERE b.organization_id = ? AND (
			    r.item_id IS NULL
			    OR (r.status = 'SUCCEEDED' AND (
			        t.id IS NULL OR t.source_account_id IS DISTINCT FROM b.payer_account_id
			        OR t.source_organization_id IS DISTINCT FROM b.organization_id
			        OR t.owner_customer_id IS DISTINCT FROM b.created_by_customer_id
			        OR t.target_account_id IS DISTINCT FROM i.payee_account_id
			        OR t.amount_minor IS DISTINCT FROM i.amount_minor
			        OR t.currency::text IS DISTINCT FROM i.currency::text
			    ))
			    OR (r.status <> 'SUCCEEDED' AND r.transfer_id IS NOT NULL)
			)
			ORDER BY b.id, i.sequence_no
			""", (rs, row) -> {
			UUID itemId = rs.getObject("item_id", UUID.class);
			UUID batchId = rs.getObject("batch_id", UUID.class);
			return finding("PAYROLL_ITEM_TRANSFER_MISMATCH", "CRITICAL", "PAYROLL_ITEM", itemId, itemId,
					facts("batchId", batchId.toString(), "resultStatus", rs.getString("result_status"),
							"payerAccountId", rs.getObject("payer_account_id", UUID.class).toString(),
							"payeeAccountId", rs.getObject("payee_account_id", UUID.class).toString(),
							"amountMinor", rs.getLong("amount_minor")),
					facts("batchStatus", rs.getString("batch_status"),
							"resultTransferId", stringUuid(rs, "result_transfer_id"),
							"actualTransferId", stringUuid(rs, "actual_transfer_id"),
							"sourceAccountId", stringUuid(rs, "source_account_id"),
							"sourceOrganizationId", stringUuid(rs, "source_organization_id"),
							"transferOwnerCustomerId", stringUuid(rs, "owner_customer_id"),
							"targetAccountId", stringUuid(rs, "target_account_id"),
							"transferAmountMinor", nullableLong(rs, "transfer_amount_minor"),
							"transferCurrency", rs.getString("transfer_currency")));
		}, organizationId);
	}

	private List<ReconciliationFinding> payrollBatchMismatches(UUID organizationId) {
		return jdbc.query("""
				SELECT b.id AS batch_id, b.status, count(i.id) AS item_count, count(r.item_id) AS result_count,
				       count(i.id) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
				       count(i.id) FILTER (WHERE r.status = 'FAILED') AS failed_count,
				       count(i.id) FILTER (WHERE r.status = 'PENDING' OR r.item_id IS NULL) AS pending_count
				FROM payroll_batches b
				LEFT JOIN payroll_items i ON i.batch_id = b.id
				LEFT JOIN payroll_item_results r ON r.item_id = i.id
				WHERE b.organization_id = ?
				GROUP BY b.id, b.status
				HAVING (b.status = 'COMPLETED' AND
				        (count(r.item_id) <> count(i.id) OR count(i.id) FILTER (WHERE r.status = 'SUCCEEDED') <> count(i.id)
				         OR count(i.id) FILTER (WHERE r.status = 'FAILED') <> 0))
				    OR (b.status = 'FAILED' AND
				        (count(r.item_id) <> count(i.id) OR count(i.id) FILTER (WHERE r.status = 'FAILED') <> count(i.id)))
				    OR (b.status = 'PARTIALLY_COMPLETED' AND
				        (count(r.item_id) <> count(i.id) OR count(i.id) FILTER (WHERE r.status = 'SUCCEEDED') = 0
				         OR count(i.id) FILTER (WHERE r.status = 'FAILED') = 0
				         OR count(i.id) FILTER (WHERE r.status = 'PENDING' OR r.item_id IS NULL) <> 0))
				    OR (b.status = 'PROCESSING' AND count(i.id) > 0 AND count(r.item_id) = count(i.id)
				        AND count(i.id) FILTER (WHERE r.status IN ('PENDING') OR r.item_id IS NULL) = 0)
				ORDER BY b.id
				""", (rs, row) -> {
			UUID batchId = rs.getObject("batch_id", UUID.class);
			return finding("PAYROLL_BATCH_TERMINAL_STATE_MISMATCH", "HIGH", "PAYROLL_BATCH", batchId, batchId,
					facts("batchStatus", rs.getString("status")),
					facts("itemCount", rs.getLong("item_count"), "resultCount", rs.getLong("result_count"),
							"succeededCount", rs.getLong("succeeded_count"), "failedCount", rs.getLong("failed_count"),
							"pendingCount", rs.getLong("pending_count")));
		}, organizationId);
	}

	private List<ReconciliationFinding> paymentMismatches(UUID organizationId) {
		return jdbc.query("""
				SELECT p.id AS payment_id, p.operation_id, p.status, p.external_status, p.amount_minor,
				       p.source_account_id, p.intent_digest, p.approved_digest, p.version, p.approved_version,
				       p.booking_journal_entry_id, p.settlement_journal_entry_id, p.reversal_journal_entry_id,
				       src.id AS source_ledger_id, ci.status AS instruction_status, ci.intent_digest AS instruction_digest,
				       booking.entry_type AS booking_type, booking.reference AS booking_reference,
				       booking.currency AS booking_currency, booking.posting_count AS booking_posting_count,
				       booking.source_debit_count AS booking_source_debit_count,
				       booking.clearing_credit_count AS booking_clearing_credit_count,
				       settlement.entry_type AS settlement_type, settlement.reference AS settlement_reference,
				       settlement.currency AS settlement_currency, settlement.posting_count AS settlement_posting_count,
				       settlement.payable_debit_count AS settlement_payable_debit_count,
				       settlement.reserve_credit_count AS settlement_reserve_credit_count,
				       refund.entry_type AS refund_type, refund.reference AS refund_reference,
				       refund.currency AS refund_currency, refund.posting_count AS refund_posting_count,
				       refund.payable_debit_count AS refund_payable_debit_count,
				       refund.source_credit_count AS refund_source_credit_count
			FROM corporate_payment_intents p
			LEFT JOIN ledger_accounts src ON src.customer_account_id = p.source_account_id
			    AND src.kind = 'CUSTOMER_DEPOSIT'
			LEFT JOIN clearing_instructions ci ON ci.operation_id = p.operation_id
			LEFT JOIN LATERAL (
			    SELECT je.entry_type, je.reference, je.currency, count(posting.id) AS posting_count,
			           count(posting.id) FILTER (WHERE posting.ledger_account_id = src.id AND posting.side = 'DEBIT'
			                                      AND posting.amount_minor = p.amount_minor) AS source_debit_count,
			           count(posting.id) FILTER (WHERE posting.ledger_account_id = ? AND posting.side = 'CREDIT'
			                                      AND posting.amount_minor = p.amount_minor) AS clearing_credit_count
			    FROM journal_entries je LEFT JOIN postings posting ON posting.journal_entry_id = je.id
			    WHERE je.id = p.booking_journal_entry_id GROUP BY je.id
			) booking ON TRUE
			LEFT JOIN LATERAL (
			    SELECT je.entry_type, je.reference, je.currency, count(posting.id) AS posting_count,
			           count(posting.id) FILTER (WHERE posting.ledger_account_id = ? AND posting.side = 'DEBIT'
			                                      AND posting.amount_minor = p.amount_minor) AS payable_debit_count,
			           count(posting.id) FILTER (WHERE posting.ledger_account_id = ? AND posting.side = 'CREDIT'
			                                      AND posting.amount_minor = p.amount_minor) AS reserve_credit_count
			    FROM journal_entries je LEFT JOIN postings posting ON posting.journal_entry_id = je.id
			    WHERE je.id = p.settlement_journal_entry_id GROUP BY je.id
			) settlement ON TRUE
			LEFT JOIN LATERAL (
			    SELECT je.entry_type, je.reference, je.currency, count(posting.id) AS posting_count,
			           count(posting.id) FILTER (WHERE posting.ledger_account_id = ? AND posting.side = 'DEBIT'
			                                      AND posting.amount_minor = p.amount_minor) AS payable_debit_count,
			           count(posting.id) FILTER (WHERE posting.ledger_account_id = src.id AND posting.side = 'CREDIT'
			                                      AND posting.amount_minor = p.amount_minor) AS source_credit_count
			    FROM journal_entries je LEFT JOIN postings posting ON posting.journal_entry_id = je.id
			    WHERE je.id = p.reversal_journal_entry_id GROUP BY je.id
			) refund ON TRUE
			WHERE p.organization_id = ?
			ORDER BY p.id
			""", (rs, row) -> paymentFinding(rs), CLEARING_PAYABLE_LEDGER, CLEARING_PAYABLE_LEDGER,
			SIMULATION_RESERVE_LEDGER, CLEARING_PAYABLE_LEDGER, organizationId).stream()
			.filter(java.util.Objects::nonNull).toList();
	}

	private ReconciliationFinding paymentFinding(ResultSet rs) throws SQLException {
		UUID paymentId = rs.getObject("payment_id", UUID.class);
		UUID operationId = rs.getObject("operation_id", UUID.class);
		String status = rs.getString("status");
		String externalStatus = rs.getString("external_status");
		long amount = rs.getLong("amount_minor");
		UUID bookingId = rs.getObject("booking_journal_entry_id", UUID.class);
		UUID settlementId = rs.getObject("settlement_journal_entry_id", UUID.class);
		UUID refundId = rs.getObject("reversal_journal_entry_id", UUID.class);
		boolean approved = !List.of("DRAFT", "SUBMITTED").contains(status)
				&& !("REJECTED".equals(status) && bookingId == null);
		boolean bookingExpected = List.of("PENDING_CLEARING", "UNKNOWN", "COMPLETED", "FAILED").contains(status)
				|| ("REJECTED".equals(status) && "REJECTED".equals(externalStatus));
		boolean refundExpected = "REJECTED".equals(status) && "REJECTED".equals(externalStatus);
		boolean settlementExpected = "COMPLETED".equals(status);
		boolean mismatch = false;
		String category = "CORPORATE_PAYMENT_JOURNAL_MISMATCH";
		if (approved && (!java.util.Objects.equals(rs.getString("intent_digest"), rs.getString("approved_digest"))
				|| rs.getInt("version") != rs.getInt("approved_version"))) {
			mismatch = true;
		}
		if (bookingExpected != (bookingId != null)
				|| (bookingId != null && !journalMatches(rs, "booking", "CORPORATE_PAYMENT_BOOKING",
						"corporate-payment:" + paymentId + ":booking", "booking_posting_count", 2,
						"booking_source_debit_count", 1, "booking_clearing_credit_count", 1))) {
			mismatch = true;
		}
		if (settlementExpected != (settlementId != null)
				|| (settlementId != null && !journalMatches(rs, "settlement", "CLEARING_SETTLEMENT",
						"corporate-payment:" + paymentId + ":settlement", "settlement_posting_count", 2,
						"settlement_payable_debit_count", 1, "settlement_reserve_credit_count", 1))) {
			mismatch = true;
		}
		if (refundExpected != (refundId != null)
				|| (refundId != null && !journalMatches(rs, "refund", "CORPORATE_PAYMENT_REFUND",
						"corporate-payment:" + paymentId + ":refund", "refund_posting_count", 2,
						"refund_payable_debit_count", 1, "refund_source_credit_count", 1))) {
			mismatch = true;
		}
		if (settlementId != null && !"SETTLED".equals(externalStatus)) {
			mismatch = true;
		}
		if (refundId != null && !"REJECTED".equals(externalStatus)) {
			mismatch = true;
		}
		if (bookingExpected && (rs.getString("instruction_status") == null
				|| !rs.getString("instruction_digest").equals(rs.getString("approved_digest")))) {
			mismatch = true;
		}
		if (externalStatus != null && !externalStatus.equals(rs.getString("instruction_status"))) {
			mismatch = true;
		}
		if (!mismatch) {
			return null;
		}
		return finding(category, "CRITICAL", "PAYMENT_INTENT", paymentId, operationId,
				facts("status", status, "externalStatus", externalStatus, "amountMinor", amount,
						"bookingRequired", bookingExpected, "settlementRequired", settlementExpected,
						"refundRequired", refundExpected),
				facts("intentDigest", rs.getString("intent_digest"), "approvedDigest", rs.getString("approved_digest"),
						"version", rs.getInt("version"), "approvedVersion", rs.getInt("approved_version"),
						"bookingJournalEntryId", stringUuid(rs, "booking_journal_entry_id"),
						"bookingEntryType", rs.getString("booking_type"), "bookingReference", rs.getString("booking_reference"),
						"bookingPostingCount", nullableLong(rs, "booking_posting_count"),
						"instructionStatus", rs.getString("instruction_status"),
						"instructionDigest", rs.getString("instruction_digest"),
						"settlementJournalEntryId", stringUuid(rs, "settlement_journal_entry_id"),
						"settlementEntryType", rs.getString("settlement_type"),
						"settlementPostingCount", nullableLong(rs, "settlement_posting_count"),
						"refundJournalEntryId", stringUuid(rs, "reversal_journal_entry_id"),
						"refundEntryType", rs.getString("refund_type"),
						"refundPostingCount", nullableLong(rs, "refund_posting_count")));
	}

	private boolean journalMatches(ResultSet rs, String prefix, String expectedType, String expectedReference,
			String postingCountColumn, long expectedPostingCount, String firstCountColumn, long firstCount,
			String secondCountColumn, long secondCount) throws SQLException {
		return expectedType.equals(rs.getString(prefix + "_type"))
				&& expectedReference.equals(rs.getString(prefix + "_reference"))
				&& "NZD".equals(rs.getString(prefix + "_currency"))
				&& nullableLong(rs, postingCountColumn) != null
				&& java.util.Objects.equals(nullableLong(rs, postingCountColumn), expectedPostingCount)
				&& java.util.Objects.equals(nullableLong(rs, firstCountColumn), firstCount)
				&& java.util.Objects.equals(nullableLong(rs, secondCountColumn), secondCount);
	}

	private List<ReconciliationFinding> missingBusinessEvents(UUID organizationId) {
		List<ReconciliationFinding> findings = new ArrayList<>();
		findings.addAll(missingEvents("""
				SELECT t.id AS resource_id, t.id AS operation_id, 'TRANSFER' AS resource_type,
				       'transfer.posted:' || t.id::text AS expected_event_key
				FROM transfers t WHERE t.source_organization_id = ?
				""", organizationId));
		findings.addAll(missingEvents("""
				SELECT b.id AS resource_id, b.id AS operation_id, 'PAYROLL_BATCH' AS resource_type, e.event_key AS expected_event_key
				FROM payroll_batches b
				CROSS JOIN LATERAL (VALUES
				    ('payroll.batch.created:' || b.id::text, TRUE),
				    ('payroll.batch.submitted:' || b.id::text, b.submitted_at IS NOT NULL),
				    ('payroll.batch.approved:' || b.id::text, b.approved_by_customer_id IS NOT NULL),
				    ('payroll.batch.rejected:' || b.id::text, b.rejected_by_customer_id IS NOT NULL),
				    ('payroll.batch.completed:' || b.id::text, b.completed_at IS NOT NULL)
				) e(event_key, required)
				WHERE b.organization_id = ? AND e.required
				""", organizationId));
		findings.addAll(missingEvents("""
				SELECT i.id AS resource_id, i.id AS operation_id, 'PAYROLL_ITEM' AS resource_type,
				       CASE r.status WHEN 'SUCCEEDED' THEN 'payroll.item.posted:' || i.id::text
				                     WHEN 'FAILED' THEN 'payroll.item.failed:' || i.id::text END AS expected_event_key
				FROM payroll_items i JOIN payroll_batches b ON b.id = i.batch_id
				JOIN payroll_item_results r ON r.item_id = i.id
				WHERE b.organization_id = ? AND r.status IN ('SUCCEEDED', 'FAILED')
				""", organizationId));
		findings.addAll(missingEvents("""
				SELECT p.id AS resource_id, p.operation_id, 'PAYMENT_INTENT' AS resource_type,
				       e.event_key AS expected_event_key
				FROM corporate_payment_intents p
				CROSS JOIN LATERAL (VALUES
				    ('corporate.payment.created:' || p.id::text, TRUE),
				    ('corporate.payment.submitted:' || p.id::text, p.submitted_at IS NOT NULL),
				    ('corporate.payment.approved:' || p.id::text, p.approved_by_customer_id IS NOT NULL),
				    ('corporate.payment.rejected:' || p.id::text, p.rejected_by_customer_id IS NOT NULL),
				    ('clearing.instruction.ready:' || p.operation_id::text, p.booking_journal_entry_id IS NOT NULL)
				) e(event_key, required)
				WHERE p.organization_id = ? AND e.required
				""", organizationId));
		findings.addAll(missingEvents("""
				SELECT i.callback_id AS resource_id, i.operation_id, 'CLEARING_CALLBACK' AS resource_type,
				       'clearing.callback.accepted:' || i.callback_id::text AS expected_event_key
				FROM clearing_callback_inbox i
				JOIN clearing_instructions c ON c.operation_id = i.operation_id
				JOIN corporate_payment_intents p ON p.operation_id = c.operation_id
				WHERE p.organization_id = ?
				""", organizationId));
		return findings;
	}

	private List<ReconciliationFinding> missingEvents(String expectedEventsSql, UUID organizationId) {
		String sql = """
				WITH expected AS (
				""" + expectedEventsSql + """
				)
				SELECT expected.resource_id, expected.operation_id, expected.resource_type, expected.expected_event_key,
				       event.id AS event_id, event.organization_id AS event_organization_id
				FROM expected
				LEFT JOIN outbox_events event ON event.event_key = expected.expected_event_key
				WHERE event.id IS NULL OR event.organization_id IS DISTINCT FROM ?
				ORDER BY expected.resource_type, expected.resource_id, expected.expected_event_key
				""";
		return jdbc.query(sql, (rs, row) -> {
			UUID resourceId = rs.getObject("resource_id", UUID.class);
			UUID operationId = rs.getObject("operation_id", UUID.class);
			String eventKey = rs.getString("expected_event_key");
			UUID eventId = rs.getObject("event_id", UUID.class);
			String category = eventId == null ? "MISSING_OUTBOX_EVENT" : "OUTBOX_ORGANIZATION_MISMATCH";
			return finding(category, "HIGH", rs.getString("resource_type"), resourceId, operationId,
					facts("eventKey", eventKey, "organizationId", organizationId.toString()),
					facts("eventId", eventId == null ? null : eventId.toString(),
							"eventOrganizationId", stringUuid(rs, "event_organization_id")));
		}, organizationId, organizationId);
	}

	private List<ReconciliationFinding> outboxOperationalFindings(UUID organizationId) {
		List<ReconciliationFinding> findings = jdbc.query("""
				SELECT id, event_key, event_type, aggregate_type, aggregate_id, status, attempt_count, last_error
				FROM outbox_events WHERE organization_id = ? AND status = 'DEAD'
				ORDER BY created_at, id
				""", (rs, row) -> finding("DEAD_OUTBOX_EVENT", "HIGH", rs.getString("aggregate_type"),
				rs.getObject("aggregate_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
				facts("status", "DELIVERED"), facts("eventId", rs.getObject("id", UUID.class).toString(),
						"eventKey", rs.getString("event_key"), "eventType", rs.getString("event_type"),
						"status", rs.getString("status"), "attemptCount", rs.getInt("attempt_count"),
						"lastError", rs.getString("last_error"))), organizationId);
		findings.addAll(jdbc.query("""
				SELECT event_key, count(*) AS event_count, min(aggregate_id::text) AS aggregate_id
				FROM outbox_events WHERE organization_id = ?
				GROUP BY event_key HAVING count(*) > 1 ORDER BY event_key
				""", (rs, row) -> finding("DUPLICATE_OUTBOX_EVENT_KEY", "CRITICAL", "OUTBOX_EVENT",
					UUID.fromString(rs.getString("aggregate_id")), null,
					facts("eventKey", rs.getString("event_key"), "eventCount", 1),
					facts("eventCount", rs.getLong("event_count"))), organizationId));
		return findings;
	}

	private List<ReconciliationFinding> organizationOwnershipMismatches(UUID organizationId) {
		return jdbc.query("""
				SELECT subject.resource_type, subject.resource_id, subject.observed_owner_id
				FROM (
				    SELECT 'TRANSFER' AS resource_type, t.id AS resource_id,
				           a.owner_organization_id AS observed_owner_id
				    FROM transfers t JOIN accounts a ON a.id = t.source_account_id
				    WHERE t.source_organization_id = ? AND a.owner_organization_id IS DISTINCT FROM t.source_organization_id
				    UNION ALL
				    SELECT 'PAYROLL_BATCH', b.id, a.owner_organization_id
				    FROM payroll_batches b JOIN accounts a ON a.id = b.payer_account_id
				    WHERE b.organization_id = ? AND a.owner_organization_id IS DISTINCT FROM b.organization_id
				    UNION ALL
				    SELECT 'PAYMENT_INTENT', p.id, a.owner_organization_id
				    FROM corporate_payment_intents p JOIN accounts a ON a.id = p.source_account_id
				    WHERE p.organization_id = ? AND a.owner_organization_id IS DISTINCT FROM p.organization_id
				) subject
				ORDER BY subject.resource_type, subject.resource_id
				""", (rs, row) -> finding("ORGANIZATION_OWNERSHIP_MISMATCH", "CRITICAL",
					rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
					rs.getObject("resource_id", UUID.class),
					facts("organizationId", organizationId.toString()),
					facts("observedOwnerOrganizationId", stringUuid(rs, "observed_owner_id"))),
				organizationId, organizationId, organizationId);
	}

	private ReconciliationFinding finding(String category, String severity, String resourceType, UUID resourceId,
			UUID operationId, Map<String, Object> expected, Map<String, Object> observed) {
		Map<String, Object> expectedFacts = new LinkedHashMap<>();
		expectedFacts.put("resourceType", resourceType);
		expectedFacts.put("resourceId", resourceId.toString());
		expectedFacts.put("recommendedAction", recommendedAction(category));
		expectedFacts.putAll(expected);
		return new ReconciliationFinding(category, severity, resourceType, resourceId, operationId,
				Map.copyOf(expectedFacts), Map.copyOf(observed), null);
	}

	private String recommendedAction(String category) {
		return switch (category) {
			case "LEDGER_PROJECTION_MISMATCH" ->
				"Rebuild the account projection from immutable postings; keep journal entries untouched.";
			case "TRANSFER_JOURNAL_RECEIPT_MISMATCH" ->
				"Hold follow-up for financial review; compare the journal and receipt without replaying the transfer.";
			case "PAYROLL_ITEM_TRANSFER_MISMATCH" ->
				"Stop batch progress and compare the approved item, result, transfer and receipt before retry.";
			case "PAYROLL_BATCH_TERMINAL_STATE_MISMATCH" ->
				"Recompute the batch from durable item results; do not force a terminal status.";
			case "CORPORATE_PAYMENT_JOURNAL_MISMATCH" ->
				"Hold settlement follow-up for independent review; use a new balanced reversal only if justified.";
			case "CLEARING_CALLBACK_MISSING", "CLEARING_SEQUENCE_MISMATCH", "CLEARING_BANK_SIMULATOR_STATE_MISMATCH",
				"CLEARING_SIMULATOR_IDENTITY_MISMATCH", "CLEARING_SIMULATOR_REFERENCE_MISMATCH",
				"CLEARING_SIMULATOR_INQUIRY_FAILED" ->
				"Inquire the same operation with the authenticated simulator; do not treat UNKNOWN as rejection.";
			case "MISSING_OUTBOX_EVENT" ->
				"Repair event delivery idempotently; do not repeat or recreate a financial posting.";
			case "OUTBOX_ORGANIZATION_MISMATCH" ->
				"Quarantine the event and review tenant attribution before any delivery.";
			case "DEAD_OUTBOX_EVENT" ->
				"Review the failure and retry through the governed outbox operation.";
			case "DUPLICATE_OUTBOX_EVENT_KEY" ->
				"Quarantine duplicate event records and verify consumer idempotency before retry.";
			case "ORGANIZATION_OWNERSHIP_MISMATCH" ->
				"Freeze cross-organization action and escalate for ownership review.";
			default -> "Investigate the persisted facts with independent review before taking action.";
		};
	}

	private static Map<String, Object> facts(Object... values) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (int index = 0; index < values.length; index += 2) {
			if (values[index + 1] != null) {
				result.put((String) values[index], values[index + 1]);
			}
		}
		return result;
	}

	private static Long nullableLong(ResultSet rs, String column) throws SQLException {
		long value = rs.getLong(column);
		return rs.wasNull() ? null : value;
	}

	private static String stringUuid(ResultSet rs, String column) throws SQLException {
		UUID value = rs.getObject(column, UUID.class);
		return value == null ? null : value.toString();
	}

	record ClearingOperationSnapshot(UUID paymentId, UUID operationId, String intentDigest,
			String paymentStatus, String externalStatus, String instructionStatus, String instructionDigest,
			String simulatorReference, Long callbackSequence, String callbackStatus) {
	}
}
