package bank.pbp.bff.payments;

import bank.pbp.bff.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.error.VendorErrors;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Customer payment entry point. Returns 202 Accepted at once; the orchestrator runs the
 * payment and the result reaches the app as a pushed event (Phase 1).
 *
 * <p>The customer is the one linked to the signed-in login (see {@link Caller}).
 */
@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService payments;
    private final RestClient orchestrator;
    private final Caller caller;

    public PaymentController(PaymentService payments, Caller caller, RestClient.Builder builder,
                             @Value("${pbp.services.orchestrator-url}") String orchestratorUrl) {
        this.payments = payments;
        this.caller = caller;
        this.orchestrator = builder.clone().baseUrl(orchestratorUrl).build();
    }

    /** A payment with its status history (INITIATED → … → COMPLETED / REJECTED / FAILED). */
    @GetMapping("/{paymentId}")
    public JsonNode get(@PathVariable UUID paymentId) {
        return VendorErrors.call("orchestrator", "payments.get", () -> orchestrator.get()
                .uri("/v1/payments/{id}", paymentId).retrieve().body(JsonNode.class));
    }

    /** The customer's latest 50 payments, outbound and inbound. */
    @GetMapping
    public JsonNode list(HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        return VendorErrors.call("orchestrator", "payments.list", () -> orchestrator.get()
                .uri("/v1/customers/{id}/payments", customerId).retrieve().body(JsonNode.class));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody PaymentRequest request,
                                                      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                                      HttpServletRequest httpRequest) {
        UUID customerId = caller.customerId(httpRequest);
        String correlationId = Correlation.current()
                .filter(id -> id.startsWith("COR-PAY-"))
                .orElseGet(() -> Ids.newCorrelationId(JourneyType.PAY));
        Correlation.set(correlationId);
        Map<String, Object> body = payments.accept(customerId, idempotencyKey.toString(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(Correlation.HEADER, String.valueOf(body.get("correlationId")))
                .body(body);
    }
}
