package nz.ac.ledgercore.clearing.simulator;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import nz.ac.ledgercore.clearing.simulator.ClearingDtos.Callback;
import nz.ac.ledgercore.clearing.simulator.ClearingDtos.OperationRequest;
import nz.ac.ledgercore.clearing.simulator.ClearingDtos.OperationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SimulatorOperationService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public SimulatorOperationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public OperationResponse submit(OperationRequest request) {
        validate(request);
        List<OperationResponse> existing = find(request.operationId(), true);
        if (!existing.isEmpty()) {
            OperationResponse current = existing.getFirst();
            if (!sameFacts(request, current)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation identity was reused with different facts.");
            }
            return current;
        }
        Integer paymentConflict = jdbc.queryForObject("SELECT count(*) FROM simulator_operations WHERE payment_id = ?",
                Integer.class, request.paymentId());
        if (paymentConflict != null && paymentConflict > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment already has a different simulator operation.");
        }

        Route route = route(request.routingCode());
        String reference = "SIM-" + request.operationId().toString().replace("-", "").substring(0, 20).toUpperCase();
        jdbc.update("""
                INSERT INTO simulator_operations (operation_id, payment_id, intent_digest, amount_minor, currency,
                    beneficiary_reference, routing_code, outcome_policy, external_status, simulator_reference)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, request.operationId(), request.paymentId(), request.intentDigest(), request.amountMinor(),
                request.currency(), request.beneficiaryReference(), request.routingCode(), route.policy(),
                route.initialStatus(), reference);
        OperationResponse response = new OperationResponse(request.operationId(), request.paymentId(),
                request.intentDigest(), route.initialStatus(), reference, 1);
        enqueueCallback(response);
        return response;
    }

    @Transactional
    public OperationResponse inquire(UUID operationId) {
        List<OperationRow> rows = jdbc.query("""
                SELECT operation_id, payment_id, intent_digest, external_status, simulator_reference, inquiry_count,
                       outcome_policy
                FROM simulator_operations WHERE operation_id = ? FOR UPDATE
                """, (rs, row) -> new OperationRow(rs.getObject("operation_id", UUID.class),
                    rs.getObject("payment_id", UUID.class), rs.getString("intent_digest").trim(),
                    rs.getString("external_status"), rs.getString("simulator_reference"),
                    rs.getInt("inquiry_count"), rs.getString("outcome_policy")), operationId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulator operation was not found.");
        }
        OperationRow current = rows.getFirst();
        String next = current.status();
        if ("ACCEPTED".equals(next)) {
            next = "PENDING";
        }
        else if ("UNKNOWN".equals(next) || "PENDING".equals(next)) {
            next = "SETTLED";
        }
        if (!next.equals(current.status())) {
            jdbc.update("""
                    UPDATE simulator_operations
                    SET external_status = ?, inquiry_count = inquiry_count + 1, updated_at = clock_timestamp()
                    WHERE operation_id = ?
                    """, next, operationId);
            OperationResponse response = new OperationResponse(current.operationId(), current.paymentId(),
                    current.digest(), next, current.reference(), current.inquiryCount() + 2L);
            enqueueCallback(response);
            return response;
        }
        jdbc.update("UPDATE simulator_operations SET inquiry_count = inquiry_count + 1, updated_at = clock_timestamp() WHERE operation_id = ?",
                operationId);
        return new OperationResponse(current.operationId(), current.paymentId(), current.digest(), current.status(),
                current.reference(), current.inquiryCount() + 1L);
    }

    private void enqueueCallback(OperationResponse operation) {
        UUID callbackId = UUID.randomUUID();
        Callback callback = new Callback(callbackId, operation.operationId(), operation.paymentId(),
                operation.intentDigest(), operation.sequenceNo(), operation.externalStatus(),
                operation.simulatorReference(), java.time.Instant.now());
        try {
            String payload = JSON.writeValueAsString(callback);
            jdbc.update("""
                    INSERT INTO simulator_callback_outbox (callback_id, operation_id, sequence_no, external_status, payload)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    """, callbackId, operation.operationId(), operation.sequenceNo(), operation.externalStatus(), payload);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Simulator callback could not be serialized.", exception);
        }
    }

    private List<OperationResponse> find(UUID operationId, boolean lock) {
        return jdbc.query("""
                SELECT operation_id, payment_id, intent_digest, external_status, simulator_reference,
                       inquiry_count
                FROM simulator_operations WHERE operation_id = ?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new OperationResponse(
                    rs.getObject("operation_id", UUID.class), rs.getObject("payment_id", UUID.class),
                    rs.getString("intent_digest").trim(), rs.getString("external_status"),
                    rs.getString("simulator_reference"), rs.getLong("inquiry_count") + 1), operationId);
    }

    private Route route(String routingCode) {
        return switch (routingCode) {
            case "SIM-SETTLE" -> new Route("SETTLE", "SETTLED");
            case "SIM-REJECT" -> new Route("REJECT", "REJECTED");
            case "SIM-ACCEPT" -> new Route("ACCEPT", "ACCEPTED");
            case "SIM-PENDING" -> new Route("PENDING", "PENDING");
            case "SIM-UNKNOWN" -> new Route("UNKNOWN", "UNKNOWN");
            default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Routing code is not in the fictional simulator catalogue.");
        };
    }

    private void validate(OperationRequest request) {
        if (request == null || request.operationId() == null || request.paymentId() == null
                || request.amountMinor() <= 0 || !"NZD".equals(request.currency())
                || request.beneficiaryReference() == null || request.beneficiaryReference().isBlank()
                || request.beneficiaryReference().length() > 80 || request.routingCode() == null
                || request.routingCode().length() > 32 || request.intentDigest() == null
                || !request.intentDigest().matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operation fields are invalid.");
        }
    }

    private boolean sameFacts(OperationRequest request, OperationResponse existing) {
        List<Boolean> matches = jdbc.query("""
                SELECT payment_id = ? AND intent_digest = ? AND amount_minor = ? AND currency = ?
                    AND beneficiary_reference = ? AND routing_code = ?
                FROM simulator_operations WHERE operation_id = ?
                """, (rs, row) -> rs.getBoolean(1), request.paymentId(), request.intentDigest(),
                request.amountMinor(), request.currency(), request.beneficiaryReference(), request.routingCode(),
                existing.operationId());
        return !matches.isEmpty() && matches.getFirst();
    }

    private record Route(String policy, String initialStatus) {
    }

    private record OperationRow(UUID operationId, UUID paymentId, String digest, String status, String reference,
            int inquiryCount, String policy) {
    }
}
