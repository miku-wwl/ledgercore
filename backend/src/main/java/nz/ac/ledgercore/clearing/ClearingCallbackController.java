package nz.ac.ledgercore.clearing;

import java.security.MessageDigest;
import java.util.HexFormat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import nz.ac.ledgercore.clearing.ClearingDtos.Callback;
import nz.ac.ledgercore.clearing.ClearingDtos.CallbackAck;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name = "ledgercore.clearing.enabled", havingValue = "true")
public class ClearingCallbackController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ClearingSignature signature;
    private final ClearingCallbackService callbacks;

    public ClearingCallbackController(ClearingSignature signature, ClearingCallbackService callbacks) {
        this.signature = signature;
        this.callbacks = callbacks;
    }

    @Operation(summary = "Accept an authenticated clearing callback",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Callback.class))))
    @PostMapping(path = "/api/clearing/callbacks", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    CallbackAck callback(@RequestHeader(ClearingSignature.TIMESTAMP_HEADER) String timestamp,
            @RequestHeader(ClearingSignature.SIGNATURE_HEADER) String signed, @RequestBody byte[] rawBody) {
        if (!signature.verify(timestamp, signed, rawBody)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Clearing callback signature is invalid or expired.");
        }
        try {
            Callback callback = JSON.readValue(rawBody, Callback.class);
            return callbacks.receive(callback, rawBody);
        }
        catch (JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clearing callback is malformed.");
        }
    }
}
