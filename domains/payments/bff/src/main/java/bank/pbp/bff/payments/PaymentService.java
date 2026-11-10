package bank.pbp.bff.payments;

import bank.pbp.bff.IdempotencyStore;
import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Accepts a payment: duplicate protection on Idempotency-Key, then a payment.create command on
 * payment-commands keyed by customer ID. No business decision is taken here.
 */
@Service
public class PaymentService {

    private final IdempotencyStore idempotency;
    private final EventPublisher events;

    public PaymentService(IdempotencyStore idempotency, EventPublisher events) {
        this.idempotency = idempotency;
        this.events = events;
    }

    public Map<String, Object> accept(UUID customerId, String idempotencyKey, PaymentRequest request) {
        String key = customerId + ":" + idempotencyKey;
        String correlationId = Correlation.current().orElseThrow();
        return idempotency.once(key, request, () -> {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("paymentId", Ids.uuidV7().toString());
            response.put("status", "ACCEPTED");
            response.put("correlationId", correlationId);
            return response;
        }, response -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("paymentId", response.get("paymentId"));
            data.put("customerId", customerId.toString());
            data.put("debtorAccount", request.debtorAccount());
            data.put("creditor", Map.of(
                    "name", request.creditor().name(),
                    "sortCode", request.creditor().sortCode(),
                    "accountNumber", request.creditor().accountNumber()));
            data.put("amount", Map.of("value", request.amount().value(), "currency", request.amount().currency()));
            data.put("purpose", request.purpose());
            data.put("remittanceInfo", request.remittanceInfo());
            data.put("copResult", request.copResult());
            data.put("scheme", request.scheme());
            data.put("idempotencyKey", key);
            // Microsecond precision matches PostgreSQL timestamptz, so the orchestrator can use it as a key.
            data.put("initiatedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
            // Phase 2 moves this to the outbox relayed by Debezium.
            events.publish(Topics.PAYMENT_COMMANDS,
                    EventEnvelope.of("payment.create", "bff", correlationId, null, customerId.toString(), data));
        });
    }
}
