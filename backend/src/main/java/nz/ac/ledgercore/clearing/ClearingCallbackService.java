package nz.ac.ledgercore.clearing;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import nz.ac.ledgercore.clearing.ClearingDtos.Callback;
import nz.ac.ledgercore.clearing.ClearingDtos.CallbackAck;
import nz.ac.ledgercore.events.DurableEventStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ClearingCallbackService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final DurableEventStore events;
    private final ApplicationEventPublisher applicationEvents;

    public ClearingCallbackService(JdbcTemplate jdbc, DurableEventStore events,
            ApplicationEventPublisher applicationEvents) {
        this.jdbc = jdbc;
        this.events = events;
        this.applicationEvents = applicationEvents;
    }

    @Transactional
    public CallbackAck receive(Callback callback, byte[] rawBody) {
        validate(callback);
        String digest = digest(rawBody);
        List<PaymentRow> payments = jdbc.query("""
                SELECT p.id, p.operation_id, p.organization_id, p.intent_digest, p.external_status, p.status,
                       p.settlement_journal_entry_id, p.reversal_journal_entry_id
                FROM corporate_payment_intents p
                JOIN clearing_instructions c ON c.operation_id = p.operation_id
                WHERE p.operation_id = ? FOR UPDATE OF p, c
                """, (rs, row) -> new PaymentRow(rs.getObject("id", UUID.class),
                    rs.getObject("operation_id", UUID.class), rs.getObject("organization_id", UUID.class),
                    rs.getString("intent_digest").trim(), rs.getString("external_status"), rs.getString("status"),
                    rs.getObject("settlement_journal_entry_id", UUID.class),
                    rs.getObject("reversal_journal_entry_id", UUID.class)), callback.operationId());
        if (payments.isEmpty()) {
            recordException(callback.operationId(), null, "UNKNOWN_CLEARING_OPERATION", "HIGH",
                    Map.of("operationId", callback.operationId().toString()), facts(callback));
            return new CallbackAck(callback.callbackId(), "EXCEPTION_RECORDED");
        }
        PaymentRow payment = payments.getFirst();
        if (!payment.paymentId().equals(callback.paymentId()) || !payment.digest().equals(callback.intentDigest())) {
            recordException(callback.operationId(), payment.organizationId(), "CLEARING_INTENT_MISMATCH", "CRITICAL",
                    Map.of("paymentId", payment.paymentId().toString(), "intentDigest", payment.digest()), facts(callback));
            return new CallbackAck(callback.callbackId(), "EXCEPTION_RECORDED");
        }

        List<String> callbackDigests = jdbc.query("SELECT payload_digest FROM clearing_callback_inbox WHERE callback_id = ?",
                (rs, row) -> rs.getString(1).trim(), callback.callbackId());
        if (!callbackDigests.isEmpty()) {
            if (callbackDigests.getFirst().equals(digest)) {
                applicationEvents.publishEvent(new ClearingOutcomeRecorded(payment.paymentId(),
                        payment.operationId(), payment.organizationId()));
                return new CallbackAck(callback.callbackId(), "DUPLICATE");
            }
            recordException(callback.operationId(), payment.organizationId(), "CLEARING_CALLBACK_ID_REUSE", "CRITICAL",
                    Map.of("payloadDigest", callbackDigests.getFirst()), Map.of("payloadDigest", digest));
            return new CallbackAck(callback.callbackId(), "EXCEPTION_RECORDED");
        }

        List<String> sequenceDigests = jdbc.query("""
                SELECT payload_digest FROM clearing_callback_inbox WHERE operation_id = ? AND sequence_no = ?
                """, (rs, row) -> rs.getString(1).trim(), callback.operationId(), callback.sequenceNo());
        if (!sequenceDigests.isEmpty()) {
            if (sequenceDigests.getFirst().equals(digest)) {
                applicationEvents.publishEvent(new ClearingOutcomeRecorded(payment.paymentId(),
                        payment.operationId(), payment.organizationId()));
                return new CallbackAck(callback.callbackId(), "DUPLICATE");
            }
            recordException(callback.operationId(), payment.organizationId(), "CLEARING_SEQUENCE_CONFLICT", "CRITICAL",
                    Map.of("payloadDigest", sequenceDigests.getFirst()), Map.of("payloadDigest", digest));
            return new CallbackAck(callback.callbackId(), "EXCEPTION_RECORDED");
        }

        Long lastSequence = jdbc.queryForObject("""
                SELECT COALESCE(max(sequence_no), 0) FROM clearing_callback_inbox WHERE operation_id = ?
                """, Long.class, callback.operationId());
        long expectedSequence = (lastSequence == null ? 0 : lastSequence) + 1;
        if (callback.sequenceNo() != expectedSequence) {
            recordException(callback.operationId(), payment.organizationId(), "CLEARING_CALLBACK_OUT_OF_ORDER", "HIGH",
                    Map.of("expectedSequence", expectedSequence), Map.of("observedSequence", callback.sequenceNo()));
            return new CallbackAck(callback.callbackId(), "EXCEPTION_RECORDED");
        }

        if (!legalTransition(payment.externalStatus(), callback.externalStatus())) {
            recordException(callback.operationId(), payment.organizationId(), "ILLEGAL_CLEARING_TRANSITION", "CRITICAL",
                    Map.of("externalStatus", String.valueOf(payment.externalStatus())),
                    Map.of("externalStatus", callback.externalStatus()));
            return new CallbackAck(callback.callbackId(), "EXCEPTION_RECORDED");
        }

        String payload;
        try {
            payload = JSON.writeValueAsString(callback);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Clearing callback could not be recorded.", exception);
        }
        jdbc.update("""
                INSERT INTO clearing_callback_inbox (callback_id, operation_id, sequence_no, external_status,
                    payload_digest, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, callback.callbackId(), callback.operationId(), callback.sequenceNo(), callback.externalStatus(),
                digest, payload);
        jdbc.update("""
                UPDATE clearing_instructions SET status = ?,
                    simulator_reference = COALESCE(simulator_reference, ?), updated_at = clock_timestamp()
                WHERE operation_id = ?
                """, callback.externalStatus(), callback.simulatorReference(), callback.operationId());
        if ("UNKNOWN".equals(callback.externalStatus()) && "PENDING_CLEARING".equals(payment.status())) {
            jdbc.update("""
                    UPDATE corporate_payment_intents SET external_status = ?, status = 'UNKNOWN',
                        updated_at = clock_timestamp() WHERE id = ?
                    """, callback.externalStatus(), payment.paymentId());
        }
        else {
            jdbc.update("""
                    UPDATE corporate_payment_intents SET external_status = ?, updated_at = clock_timestamp()
                    WHERE id = ?
                    """, callback.externalStatus(), payment.paymentId());
        }
        events.recordAudit(null, "CLEARING_CALLBACK_ACCEPTED", "CORPORATE_PAYMENT", payment.paymentId(),
                payment.organizationId(), Map.of("callbackId", callback.callbackId().toString(),
                        "sequenceNo", callback.sequenceNo(), "externalStatus", callback.externalStatus()));
        events.enqueue("clearing.callback.accepted:" + callback.callbackId(), "clearing.callback.accepted",
                "CLEARING_INSTRUCTION", callback.operationId(), null, payment.organizationId(),
                Map.of("callbackId", callback.callbackId().toString(), "sequenceNo", callback.sequenceNo(),
                        "externalStatus", callback.externalStatus()));
        applicationEvents.publishEvent(new ClearingOutcomeRecorded(payment.paymentId(),
                payment.operationId(), payment.organizationId()));
        return new CallbackAck(callback.callbackId(), "ACCEPTED");
    }

    private void recordException(UUID operationId, UUID organizationId, String category, String severity,
            Map<String, ?> expected, Map<String, ?> observed) {
        try {
            jdbc.update("""
                    INSERT INTO reconciliation_exceptions (id, category, severity, operation_id, organization_id,
                        expected_facts, observed_facts)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    """, UUID.randomUUID(), category, severity, operationId, organizationId,
                    JSON.writeValueAsString(expected), JSON.writeValueAsString(observed));
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Clearing reconciliation exception could not be recorded.", exception);
        }
    }

    private boolean legalTransition(String current, String next) {
        if (current == null) {
            return true;
        }
        return switch (current) {
            case "ACCEPTED" -> List.of("PENDING", "UNKNOWN", "SETTLED", "REJECTED").contains(next);
            case "PENDING" -> List.of("UNKNOWN", "SETTLED", "REJECTED").contains(next);
            case "UNKNOWN" -> List.of("SETTLED", "REJECTED").contains(next);
            default -> current.equals(next);
        };
    }

    private void validate(Callback callback) {
        if (callback == null || callback.callbackId() == null || callback.operationId() == null
                || callback.paymentId() == null || callback.intentDigest() == null
                || !callback.intentDigest().matches("[0-9a-f]{64}") || callback.sequenceNo() < 1
                || callback.simulatorReference() == null || callback.simulatorReference().isBlank()
                || callback.occurredAt() == null || !List.of("ACCEPTED", "PENDING", "SETTLED", "REJECTED", "UNKNOWN")
                        .contains(callback.externalStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clearing callback fields are invalid.");
        }
    }

    private Map<String, String> facts(Callback callback) {
        return Map.of("paymentId", callback.paymentId().toString(), "intentDigest", callback.intentDigest(),
                "sequenceNo", Long.toString(callback.sequenceNo()), "externalStatus", callback.externalStatus());
    }

    private String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (Exception exception) {
            throw new IllegalStateException("Clearing callback digest could not be computed.", exception);
        }
    }

    private record PaymentRow(UUID paymentId, UUID operationId, UUID organizationId, String digest,
            String externalStatus, String status, UUID settlementEntryId, UUID reversalEntryId) {
    }

}
