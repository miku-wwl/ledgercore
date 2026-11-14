package nz.ac.ledgercore.clearing.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import nz.ac.ledgercore.clearing.simulator.ClearingDtos.InquiryRequest;
import nz.ac.ledgercore.clearing.simulator.ClearingDtos.OperationRequest;
import nz.ac.ledgercore.clearing.simulator.ClearingDtos.OperationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

@ActiveProfiles("test")
@SpringBootTest
class SimulatorOperationPostgresIntegrationTests {

    @Autowired
    SimulatorOperationService operations;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void submissionIsDurableAndSameOperationIdentityIsIdempotent() {
        OperationRequest request = request("SIM-SETTLE");
        OperationResponse first = operations.submit(request);
        OperationResponse replay = operations.submit(request);
        assertThat(first).isEqualTo(replay);
        assertThat(first.externalStatus()).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulator_operations WHERE operation_id = ?",
                Integer.class, request.operationId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulator_callback_outbox WHERE operation_id = ?",
                Integer.class, request.operationId())).isEqualTo(1);
    }

    @Test
    void unknownOutcomeCanBeResolvedByInquiryWithoutChangingOperationIdentity() {
        OperationRequest request = request("SIM-UNKNOWN");
        OperationResponse initial = operations.submit(request);
        assertThat(initial.externalStatus()).isEqualTo("UNKNOWN");
        OperationResponse resolved = operations.inquire(request.operationId());
        assertThat(resolved.externalStatus()).isEqualTo("SETTLED");
        assertThat(resolved.operationId()).isEqualTo(initial.operationId());
        assertThat(resolved.paymentId()).isEqualTo(initial.paymentId());
        assertThat(resolved.intentDigest()).isEqualTo(initial.intentDigest());
        assertThat(resolved.sequenceNo()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulator_callback_outbox WHERE operation_id = ?",
                Integer.class, request.operationId())).isEqualTo(2);
        OperationResponse terminalInquiry = operations.inquire(request.operationId());
        assertThat(terminalInquiry.externalStatus()).isEqualTo("SETTLED");
        assertThat(terminalInquiry.sequenceNo()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulator_callback_outbox WHERE operation_id = ?",
                Integer.class, request.operationId())).isEqualTo(2);
    }

    @Test
    void repeatedInquiryOfTerminalOperationKeepsPersistedCallbackSequence() {
        OperationRequest request = request("SIM-SETTLE");
        OperationResponse initial = operations.submit(request);
        OperationResponse firstInquiry = operations.inquire(request.operationId());
        OperationResponse secondInquiry = operations.inquire(request.operationId());
        OperationResponse replay = operations.submit(request);

        assertThat(initial.sequenceNo()).isEqualTo(1);
        assertThat(firstInquiry.sequenceNo()).isEqualTo(1);
        assertThat(secondInquiry.sequenceNo()).isEqualTo(1);
        assertThat(replay.sequenceNo()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT inquiry_count FROM simulator_operations WHERE operation_id = ?",
                Integer.class, request.operationId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulator_callback_outbox WHERE operation_id = ?",
                Integer.class, request.operationId())).isEqualTo(1);
    }

    @Test
    void acceptedOperationMovesThroughPendingBeforeSettlement() {
        OperationRequest request = request("SIM-ACCEPT");
        assertThat(operations.submit(request).externalStatus()).isEqualTo("ACCEPTED");
        assertThat(operations.inquire(request.operationId()).externalStatus()).isEqualTo("PENDING");
        OperationResponse settled = operations.inquire(request.operationId());
        assertThat(settled.externalStatus()).isEqualTo("SETTLED");
        assertThat(settled.sequenceNo()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulator_callback_outbox WHERE operation_id = ?",
                Integer.class, request.operationId())).isEqualTo(3);
    }

    @Test
    void operationIdentityCannotBeReusedWithDifferentFinancialFacts() {
        OperationRequest request = request("SIM-PENDING");
        operations.submit(request);
        OperationRequest changedAmount = new OperationRequest(request.operationId(), request.paymentId(),
                request.intentDigest(), request.amountMinor() + 1, request.currency(),
                request.beneficiaryReference(), request.routingCode());
        assertThatThrownBy(() -> operations.submit(changedAmount)).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulator_callback_outbox WHERE operation_id = ?",
                Integer.class, request.operationId())).isEqualTo(1);
    }

    @Test
    void preservesCorrelationIdForCallbackDispatchAcrossOperationAndInquiry() {
        OperationRequest request = request("SIM-UNKNOWN");
        String correlationId = "e9-" + UUID.randomUUID();
        operations.submit(request, correlationId);
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM simulator_operations WHERE operation_id = ?",
                String.class, request.operationId())).isEqualTo(correlationId);
        assertThat(jdbc.queryForObject("""
                SELECT o.correlation_id
                FROM simulator_callback_outbox c
                JOIN simulator_operations o ON o.operation_id = c.operation_id
                WHERE c.operation_id = ?
                """, String.class, request.operationId())).isEqualTo(correlationId);
        operations.inquire(request.operationId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM simulator_callback_outbox c
                JOIN simulator_operations o ON o.operation_id = c.operation_id
                WHERE c.operation_id = ? AND o.correlation_id = ?
                """, Integer.class, request.operationId(), correlationId)).isEqualTo(2);
    }

    private OperationRequest request(String routingCode) {
        return new OperationRequest(UUID.randomUUID(), UUID.randomUUID(), "b".repeat(64), 12_345,
                "NZD", "TEST-" + UUID.randomUUID(), routingCode);
    }
}
