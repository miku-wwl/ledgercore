package nz.ac.ledgercore.payments;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import nz.ac.ledgercore.identity.CustomerPrincipal;
import nz.ac.ledgercore.payments.CorporatePaymentService.NewPayment;
import nz.ac.ledgercore.payments.CorporatePaymentService.PaymentPage;
import nz.ac.ledgercore.payments.CorporatePaymentService.PaymentView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class CorporatePaymentController {

    private final CorporatePaymentService paymentService;

    public CorporatePaymentController(CorporatePaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/api/organizations/{organizationId}/payments")
    PaymentPage list(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID organizationId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return paymentService.list(principal.customerId(), organizationId, page, size);
    }

    @PostMapping("/api/organizations/{organizationId}/payments")
    ResponseEntity<PaymentView> create(@AuthenticationPrincipal CustomerPrincipal principal,
            @PathVariable UUID organizationId, @Valid @RequestBody CreatePaymentRequest request) {
        PaymentView payment = paymentService.createDraft(principal.customerId(), organizationId,
                new NewPayment(request.sourceAccountId(), request.beneficiaryName(),
                        request.beneficiaryAccountReference(), request.beneficiaryRoutingCode(), request.amount()));
        return ResponseEntity.created(URI.create("/api/payments/" + payment.id())).body(payment);
    }

    @GetMapping("/api/payments/{paymentId}")
    PaymentView get(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID paymentId) {
        return paymentService.get(principal.customerId(), paymentId);
    }

    @GetMapping("/api/payments")
    List<PaymentView> recent(@AuthenticationPrincipal CustomerPrincipal principal,
            @RequestParam(defaultValue = "50") int limit) {
        return paymentService.recent(principal.customerId(), limit);
    }

    @PostMapping("/api/payments/{paymentId}/submit")
    PaymentView submit(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID paymentId) {
        return paymentService.submit(principal.customerId(), paymentId);
    }

    @PostMapping("/api/payments/{paymentId}/approve")
    PaymentView approve(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID paymentId) {
        return paymentService.approve(principal.customerId(), paymentId);
    }

    @PostMapping("/api/payments/{paymentId}/reject")
    PaymentView reject(@AuthenticationPrincipal CustomerPrincipal principal, @PathVariable UUID paymentId,
            @Valid @RequestBody RejectPaymentRequest request) {
        return paymentService.reject(principal.customerId(), paymentId, request.reason());
    }

    public record CreatePaymentRequest(@NotNull UUID sourceAccountId,
            @NotBlank @Size(max = 120) String beneficiaryName,
            @NotBlank @Size(max = 80) String beneficiaryAccountReference,
            @NotBlank @Size(max = 32) String beneficiaryRoutingCode,
            @NotBlank @Pattern(regexp = "(?:0|[1-9][0-9]{0,11})\\.[0-9]{2}") String amount) {
    }

    public record RejectPaymentRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
