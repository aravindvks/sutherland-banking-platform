package bank.pbp.bff.payments;

import bank.pbp.bff.security.Caller;
import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.error.VendorErrors;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Direct Debits the customer pays (Bacs). Originators set mandates up through Bacs; here the
 * customer sees them, cancels them, and claims a refund under the Direct Debit Guarantee.
 * Commands go to payment-commands (directdebit.cancel, directdebit.claim).
 */
@RestController
@RequestMapping("/v1/direct-debits")
public class DirectDebitController {

    private final EventPublisher events;
    private final RestClient orchestrator;
    private final Caller caller;

    public DirectDebitController(EventPublisher events, Caller caller, RestClient.Builder builder,
                                 @Value("${pbp.services.orchestrator-url}") String url) {
        this.events = events;
        this.caller = caller;
        this.orchestrator = builder.clone().baseUrl(url).build();
    }

    @GetMapping
    public JsonNode list(HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        return VendorErrors.call("orchestrator", "mandates.list", () -> orchestrator.get()
                .uri("/v1/customers/{id}/mandates", customerId).retrieve().body(JsonNode.class));
    }

    @DeleteMapping("/{mandateId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID mandateId, HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mandateId", mandateId.toString());
        return command("directdebit.cancel", data, request);
    }

    /** Direct Debit Guarantee: a full and immediate refund of a Direct Debit paid in error. */
    @PostMapping("/claims")
    public ResponseEntity<Map<String, Object>> claim(@Valid @RequestBody NewClaim body, HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", body.paymentId().toString());
        data.put("reasonCode", body.reasonCode() == null ? "1" : body.reasonCode());
        return command("directdebit.claim", data, request);
    }

    private ResponseEntity<Map<String, Object>> command(String type, Map<String, Object> data, HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        String correlationId = Ids.newCorrelationId(JourneyType.PAY);
        Correlation.set(correlationId);
        events.publish(Topics.PAYMENT_COMMANDS,
                EventEnvelope.of(type, "bff", correlationId, null, customerId.toString(), data));
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("status", "ACCEPTED");
        out.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(Correlation.HEADER, correlationId).body(out);
    }

    /** reasonCode: the DDICA reason, 1 (amount or date differs from the advance notice) by default. */
    public record NewClaim(@NotNull UUID paymentId, @Pattern(regexp = "[0-9]") String reasonCode) {
    }
}
