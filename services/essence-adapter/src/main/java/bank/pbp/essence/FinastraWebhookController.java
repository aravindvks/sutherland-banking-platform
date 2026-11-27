package bank.pbp.essence;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.error.PbpException;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import bank.pbp.common.webhook.WebhookSignature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finastra notification intake (LLD section 6): payment status, inbound credits (Faster Payments
 * and Bacs) and Direct Debits. Checks the signature, normalises to our vocabulary and publishes to
 * payment-events: {@code payment.scheme_status}, {@code credit.received},
 * {@code directdebit.mandate_received} and {@code directdebit.collection_presented}.
 * The orchestrator decides what they mean.
 */
@RestController
public class FinastraWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FinastraWebhookController.class);

    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final String secret;
    private final long windowSeconds;

    private final String currency;

    public FinastraWebhookController(EventPublisher events, ObjectMapper mapper,
                                     @Value("${webhook.secret}") String secret,
                                     @Value("${webhook.replay-window-seconds}") long windowSeconds,
                                     bank.pbp.common.market.MarketPack market) {
        this.currency = market.currency().getCurrencyCode();   // when Essence leaves the currency out
        this.events = events;
        this.mapper = mapper;
        this.secret = secret;
        this.windowSeconds = windowSeconds;
    }

    @PostMapping("/hooks/finastra")
    public Map<String, String> receive(@RequestBody String body,
                                       @RequestHeader(value = WebhookSignature.TIMESTAMP_HEADER, required = false) String ts,
                                       @RequestHeader(value = WebhookSignature.SIGNATURE_HEADER, required = false) String sig) {
        if (!WebhookSignature.verify(secret, ts, body, sig, windowSeconds)) {
            throw new PbpException("GEN-AUTH-001", 401, "Webhook signature invalid",
                    "Signature or timestamp check failed");
        }
        JsonNode n = read(body);
        String type = n.path("type").asText();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vendorEventId", n.path("eventId").asText());
        data.put("finastraRef", n.path("finastraRef").asText());
        data.put("scheme", n.path("scheme").asText());

        switch (type) {
            case "payment.status" -> {
                // endToEndId is our payment ID, echoed by Finastra.
                data.put("paymentId", n.path("endToEndId").asText());
                data.put("status", n.path("status").asText());
                data.put("reason", n.path("reason").isNull() ? null : n.path("reason").asText(null));
                events.publish(Topics.PAYMENT_EVENTS,
                        EventEnvelope.of("payment.scheme_status", "essence-adapter", null, null, null, data));
            }
            case "credit.received" -> {
                String correlationId = Ids.newCorrelationId(JourneyType.PAY);
                Correlation.set(correlationId);
                data.put("creditAccount", n.path("creditAccountId").asText());
                data.put("amount", Map.of("value", n.path("amount").asText(), "currency", n.path("currency").asText(currency)));
                data.put("debtorName", n.path("debtorName").asText());
                data.put("debtorSortCode", n.path("debtorSortCode").asText());
                data.put("reference", n.path("reference").asText());
                events.publish(Topics.PAYMENT_EVENTS,
                        EventEnvelope.of("credit.received", "essence-adapter", correlationId, null, null, data));
            }
            case "dd.mandate" -> {
                // AUDDIS: an originator lodged a new Direct Debit Instruction on one of our accounts.
                String correlationId = Ids.newCorrelationId(JourneyType.PAY);
                Correlation.set(correlationId);
                data.put("mandateRef", n.path("mandateRef").asText());
                data.put("account", n.path("accountId").asText());
                data.put("originatorName", n.path("originatorName").asText());
                data.put("serviceUserNumber", n.path("serviceUserNumber").asText());
                data.put("payerReference", n.path("payerReference").asText());
                data.put("auddisCode", n.path("auddisCode").asText());
                events.publish(Topics.PAYMENT_EVENTS,
                        EventEnvelope.of("directdebit.mandate_received", "essence-adapter", correlationId, null, null, data));
            }
            case "dd.collection" -> {
                // Bacs presents a collection against a mandate; the orchestrator decides pay or return.
                String correlationId = Ids.newCorrelationId(JourneyType.PAY);
                Correlation.set(correlationId);
                data.put("collectionRef", n.path("collectionRef").asText());
                data.put("mandateRef", n.path("mandateRef").asText());
                data.put("account", n.path("accountId").asText());
                data.put("amount", Map.of("value", n.path("amount").asText(), "currency", n.path("currency").asText(currency)));
                data.put("dueDate", n.path("dueDate").asText());
                data.put("originatorName", n.path("originatorName").asText());
                data.put("serviceUserNumber", n.path("serviceUserNumber").asText());
                data.put("payerReference", n.path("payerReference").asText());
                events.publish(Topics.PAYMENT_EVENTS,
                        EventEnvelope.of("directdebit.collection_presented", "essence-adapter", correlationId, null, null,
                                data));
            }
            default -> log.info("webhook.ignored vendor=finastra type={}", type);
        }
        return Map.of("status", "RECEIVED");
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new PbpException("GEN-VALID-001", 400, "Request is not valid", "Webhook body is not JSON");
        }
    }
}
