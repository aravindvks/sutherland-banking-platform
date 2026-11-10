package bank.pbp.orchestrator.payments;

import static bank.pbp.orchestrator.temporal.ActivitySupport.call;
import static bank.pbp.orchestrator.temporal.ActivitySupport.withCorrelation;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.orchestrator.payments.InboundPaymentWorkflow.InboundCredit;
import bank.pbp.orchestrator.payments.InboundPaymentWorkflow.Owner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Component
public class InboundActivitiesImpl implements InboundPaymentWorkflow.InboundActivities {

    private static final Logger log = LoggerFactory.getLogger(InboundActivitiesImpl.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final RestClient mapping;

    public InboundActivitiesImpl(JdbcClient jdbc, TransactionTemplate tx, EventPublisher events, ObjectMapper mapper,
                                 @Qualifier("mappingClient") RestClient mapping) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.mapper = mapper;
        this.mapping = mapping;
    }

    @Override
    public Owner owner(InboundCredit c) {
        return withCorrelation(c.correlationId(), () -> {
            JsonNode r = call("mapping.resolve", () -> mapping.get()
                    .uri(u -> u.path("/v1/resolve").queryParam("system", "ESSENCE").queryParam("type", "ACCOUNT")
                            .queryParam("id", c.creditAccount()).build())
                    .retrieve().body(JsonNode.class));
            return new Owner(r.path("customer").path("customerId").asText(),
                    r.path("customer").path("status").asText());
        });
    }

    @Override
    public void record(InboundCredit c, String customerId) {
        withCorrelation(c.correlationId(), () -> tx.execute(s -> {
            Map<String, String> creditor = Map.of("account", c.creditAccount(), "debtorName", c.debtorName(),
                    "debtorSortCode", c.debtorSortCode(), "reference", c.reference(),
                    "scheme", c.scheme() == null || c.scheme().isBlank() ? "FPS" : c.scheme());
            int inserted = jdbc.sql("""
                            INSERT INTO payment (payment_id, correlation_id, customer_id, debit_account, creditor,
                                                 amount, currency, payment_type, status, finastra_ref,
                                                 idempotency_key, created_at)
                            VALUES (:id, :correlationId, :customerId, :debit, CAST(:creditor AS jsonb), :amount,
                                    :currency, 'INBOUND', 'RECEIVED', :ref, :idem, :createdAt)
                            ON CONFLICT DO NOTHING""")
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("correlationId", c.correlationId())
                    .param("customerId", UUID.fromString(customerId))
                    .param("debit", c.debtorSortCode() + " " + c.debtorName())
                    .param("creditor", json(creditor))
                    .param("amount", new BigDecimal(c.amount()))
                    .param("currency", c.currency())
                    .param("ref", c.finastraRef())
                    .param("idem", "finastra:" + c.finastraRef())
                    .param("createdAt", OffsetDateTime.parse(c.receivedAt()))
                    .update();
            if (inserted == 1) {
                jdbc.sql("""
                                INSERT INTO payment_status_history (payment_id, from_status, to_status, actor, correlation_id)
                                VALUES (:id, NULL, 'RECEIVED', 'vendor:finastra', :correlationId)""")
                        .param("id", UUID.fromString(c.paymentId()))
                        .param("correlationId", c.correlationId())
                        .update();
            }
            return null;
        }));
    }

    @Override
    public void settle(InboundCredit c, String customerId, String status, String reason) {
        boolean moved = withCorrelation(c.correlationId(), () -> Boolean.TRUE.equals(tx.execute(s -> {
            int rows = jdbc.sql("""
                            UPDATE payment SET status = :status
                            WHERE payment_id = :id AND created_at = :createdAt AND status = 'RECEIVED'""")
                    .param("status", status)
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("createdAt", OffsetDateTime.parse(c.receivedAt()))
                    .update();
            if (rows == 0) {
                return false;
            }
            jdbc.sql("""
                            INSERT INTO payment_status_history (payment_id, from_status, to_status, reason, actor,
                                                                correlation_id)
                            VALUES (:id, 'RECEIVED', :status, :reason, 'svc:orchestrator', :correlationId)""")
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("status", status)
                    .param("reason", reason)
                    .param("correlationId", c.correlationId())
                    .update();
            return true;
        })));
        if (!moved) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", c.paymentId());
        data.put("finastraRef", c.finastraRef());
        data.put("creditAccount", c.creditAccount());
        data.put("amount", Map.of("value", c.amount(), "currency", c.currency()));
        data.put("debtorName", c.debtorName());
        data.put("reference", c.reference());
        data.put("scheme", c.scheme());
        data.put("status", status);
        data.put("reason", reason);
        withCorrelation(c.correlationId(), () -> {
            events.publish(Topics.PAYMENT_EVENTS,
                    EventEnvelope.of("payment.received", "orchestrator", c.correlationId(), null, customerId, data));
            return null;
        });
        log.info("payment.received paymentId={} account={} amount={} status={}", c.paymentId(), c.creditAccount(),
                c.amount(), status);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
