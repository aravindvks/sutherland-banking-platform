package bank.pbp.bff.cards;

import bank.pbp.bff.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.error.PbpException;
import bank.pbp.common.error.VendorErrors;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Card controls from the customer app: freeze, daily limit, online / contactless / ATM.
 * Publishes card.controls.update on card-commands; the orchestrator applies it at Thredd and in
 * the mapping service, and the EHI responder enforces it on the next authorisation.
 */
@RestController
public class CardController {

    private final EventPublisher events;
    private final RestClient mapping;
    private final Caller caller;

    public CardController(EventPublisher events, Caller caller, RestClient.Builder builder,
                          @Value("${pbp.services.mapping-url}") String mappingUrl) {
        this.events = events;
        this.caller = caller;
        this.mapping = builder.clone().baseUrl(mappingUrl).build();
    }

    @PutMapping("/v1/cards/controls")
    public ResponseEntity<Map<String, Object>> controls(@Valid @RequestBody Controls req,
                                                        HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        JsonNode graph = VendorErrors.call("mapping", "customers.graph", () -> mapping.get()
                .uri("/v1/customers/{id}/graph", customerId).retrieve().body(JsonNode.class));
        JsonNode card = null;
        for (JsonNode ref : graph.path("refs")) {
            // The debit card; credit cards (metadata product CREDIT) are not changed from here.
            if ("CARD".equals(ref.path("entityType").asText())
                    && !"CREDIT".equals(ref.path("metadata").path("product").asText())) {
                card = ref;
            }
        }
        if (card == null) {
            throw new PbpException("CRD-STATE-001", 409, "Card action not allowed", "This customer has no card");
        }
        String correlationId = Ids.newCorrelationId(JourneyType.CRD);
        Correlation.set(correlationId);
        String commandId = Ids.uuidV7().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commandId", commandId);
        data.put("token", card.path("externalId").asText());
        data.put("refId", card.path("refId").asText());
        data.put("frozen", req.frozen());
        data.put("dailyLimit", req.dailyLimit());
        data.put("online", req.online());
        data.put("contactless", req.contactless());
        data.put("atm", req.atm());
        events.publish(Topics.CARD_COMMANDS, EventEnvelope.of("card.controls.update", "bff", correlationId, null,
                customerId.toString(), data));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("commandId", commandId, "status", "ACCEPTED", "correlationId", correlationId));
    }

    public record Controls(boolean frozen,
                           @NotBlank @Pattern(regexp = "100\\.00|300\\.00|1000\\.00") String dailyLimit,
                           boolean online, boolean contactless, boolean atm) {
    }
}
