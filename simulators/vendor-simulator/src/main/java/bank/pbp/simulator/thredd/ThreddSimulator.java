package bank.pbp.simulator.thredd;

import bank.pbp.simulator.Scenario;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Thredd stand-in: card issue and status, plus the card network side of a purchase.
 * A "card tap" makes the simulator call our EHI responder, the same direction as the real
 * Thredd EHI. Tokens only; no PAN ever leaves the simulator.
 */
@RestController
@RequestMapping("/thredd/v1")
public class ThreddSimulator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Map<String, Object>> cardsByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, String> statusByToken = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> controlsByToken = new ConcurrentHashMap<>();
    private final RestClient ehi;
    private final String ehiUrl;

    private final String currency;

    public ThreddSimulator(RestClient.Builder builder, @Value("${sim.thredd.ehi-url}") String ehiUrl,
                           bank.pbp.common.market.MarketPack market) {
        this.currency = market.currency().getCurrencyCode();
        this.ehi = builder.build();
        this.ehiUrl = ehiUrl;
    }

    @PostMapping("/cards")
    public ResponseEntity<Map<String, Object>> issue(@RequestBody Map<String, Object> request,
                                                     @RequestHeader(value = "IdempotencyKey", required = false) String key,
                                                     @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        if (key != null && cardsByIdempotencyKey.containsKey(key)) {
            return ResponseEntity.ok(cardsByIdempotencyKey.get(key));
        }
        String token = "thr_tok_" + Integer.toHexString(0x100000 + RANDOM.nextInt(0xEFFFFF));
        Map<String, Object> card = Map.of(
                "token", token,
                "last4", String.format("%04d", RANDOM.nextInt(10_000)),
                "status", "ACTIVE",
                "accountRef", String.valueOf(request.getOrDefault("accountRef", "")));
        statusByToken.put(token, "ACTIVE");
        if (key != null) {
            cardsByIdempotencyKey.put(key, card);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(card);
    }

    @PostMapping("/cards/{token}/status")
    public Map<String, Object> status(@PathVariable String token, @RequestBody Map<String, Object> request) {
        String status = String.valueOf(request.get("status"));
        statusByToken.put(token, status);
        return Map.of("token", token, "status", status);
    }

    /**
     * Demo and test control: a purchase at a merchant. Sends an EHI authorisation to the bank and
     * returns the bank's decision, as the card network would see it.
     */
    @org.springframework.web.bind.annotation.PutMapping("/cards/{token}/controls")
    public Map<String, Object> controls(@PathVariable String token, @RequestBody Map<String, Object> controls) {
        controlsByToken.put(token, controls);
        return controls;
    }

    /**
     * Demo and test control: the merchant settles an earlier purchase. Sends an EHI presentment
     * (or a reversal with {@code "reverse": true}) for the authorisation's message ID.
     */
    @PostMapping("/sim/settle")
    public ResponseEntity<Map<String, Object>> settle(@RequestBody Map<String, Object> req) {
        boolean reverse = Boolean.parseBoolean(String.valueOf(req.getOrDefault("reverse", "false")));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", "EHI-" + UUID.randomUUID());
        message.put("messageType", reverse ? "REVERSAL" : "PRESENTMENT");
        message.put("cardToken", String.valueOf(req.get("token")));
        message.put("amount", String.valueOf(req.get("amount")));
        message.put("currency", currency);
        message.put("originalMessageId", String.valueOf(req.get("messageId")));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> ack = ehi.post().uri(ehiUrl).body(message).retrieve().body(Map.class);
            return ResponseEntity.ok(ack);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.toString()));
        }
    }

    @PostMapping("/sim/card-tap")
    public ResponseEntity<Map<String, Object>> cardTap(@RequestBody Map<String, Object> tap) {
        Map<String, Object> merchant = new LinkedHashMap<>();
        merchant.put("name", String.valueOf(tap.getOrDefault("merchant", "Bean There Cafe")));
        merchant.put("mcc", String.valueOf(tap.getOrDefault("mcc", "5814")));
        merchant.put("channel", String.valueOf(tap.getOrDefault("channel", "contactless")));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", "EHI-" + UUID.randomUUID());
        message.put("messageType", "AUTHORISATION");
        message.put("cardToken", String.valueOf(tap.get("token")));
        message.put("amount", String.valueOf(tap.getOrDefault("amount", "3.50")));
        message.put("currency", currency);
        message.put("merchant", merchant);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> decision = ehi.post().uri(ehiUrl).body(message).retrieve().body(Map.class);
            return ResponseEntity.ok(decision);
        } catch (RestClientResponseException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("decision", "DECLINE", "reason", "EHI_ERROR " + e.getStatusCode().value()));
        } catch (RuntimeException e) {
            // Thredd stands in when the bank does not answer in time; the prototype STIP rule is decline.
            return ResponseEntity.ok(Map.of("decision", "DECLINE", "reason", "STIP_TIMEOUT"));
        }
    }
}
