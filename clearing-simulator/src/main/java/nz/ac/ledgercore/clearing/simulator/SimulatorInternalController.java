package nz.ac.ledgercore.clearing.simulator;

import java.nio.charset.StandardCharsets;

import nz.ac.ledgercore.clearing.simulator.ClearingDtos.InquiryRequest;
import nz.ac.ledgercore.clearing.simulator.ClearingDtos.OperationRequest;
import nz.ac.ledgercore.clearing.simulator.ClearingDtos.OperationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class SimulatorInternalController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ClearingSignature signature;
    private final SimulatorOperationService operations;

    public SimulatorInternalController(ClearingSignature signature, SimulatorOperationService operations) {
        this.signature = signature;
        this.operations = operations;
    }

    @PostMapping(path = "/internal/operations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    OperationResponse submit(@RequestHeader(ClearingSignature.TIMESTAMP_HEADER) String timestamp,
            @RequestHeader(ClearingSignature.SIGNATURE_HEADER) String signed,
            @RequestBody byte[] rawBody) {
        verify(timestamp, signed, rawBody);
        try {
            return operations.submit(JSON.readValue(rawBody, OperationRequest.class),
                    org.slf4j.MDC.get("correlationId"));
        }
        catch (JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operation request is malformed.");
        }
    }

    @PostMapping(path = "/internal/operations/{operationId}/inquiry", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    OperationResponse inquire(@PathVariable java.util.UUID operationId,
            @RequestHeader(ClearingSignature.TIMESTAMP_HEADER) String timestamp,
            @RequestHeader(ClearingSignature.SIGNATURE_HEADER) String signed, @RequestBody byte[] rawBody) {
        verify(timestamp, signed, rawBody);
        try {
            InquiryRequest request = JSON.readValue(rawBody, InquiryRequest.class);
            if (!operationId.equals(request.operationId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inquiry operation identity does not match the path.");
            }
            return operations.inquire(operationId, request.afterSequence());
        }
        catch (JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inquiry request is malformed.");
        }
    }

    private void verify(String timestamp, String signed, byte[] rawBody) {
        if (!signature.verify(timestamp, signed, rawBody)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Clearing request signature is invalid or expired.");
        }
    }
}
