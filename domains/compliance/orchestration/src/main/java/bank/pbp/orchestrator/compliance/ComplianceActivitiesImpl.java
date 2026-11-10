package bank.pbp.orchestrator.compliance;

import static bank.pbp.orchestrator.temporal.ActivitySupport.call;
import static bank.pbp.orchestrator.temporal.ActivitySupport.withCorrelation;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ComplianceActivitiesImpl implements ComplianceWorkflow.ComplianceActivities {

    private static final Logger log = LoggerFactory.getLogger(ComplianceActivitiesImpl.class);

    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final RestClient mapping;
    private final RestClient thredd;

    public ComplianceActivitiesImpl(JdbcClient jdbc, EventPublisher events,
                                    @Qualifier("mappingClient") RestClient mapping,
                                    @Qualifier("threddClient") RestClient thredd) {
        this.jdbc = jdbc;
        this.events = events;
        this.mapping = mapping;
        this.thredd = thredd;
    }

    @Override
    public void startSaga(String sagaId, String customerId, String correlationId) {
        jdbc.sql("""
                        INSERT INTO saga (saga_id, saga_type, customer_id, correlation_id, current_step, status)
                        VALUES (:id, 'FREEZE', :customerId, :correlationId, 'SCREENING_HIT', 'RUNNING')
                        ON CONFLICT DO NOTHING""")
                .param("id", UUID.fromString(sagaId))
                .param("customerId", UUID.fromString(customerId))
                .param("correlationId", correlationId)
                .update();
    }

    @Override
    public boolean freezeCustomer(String customerId, String correlationId, String reason) {
        return withCorrelation(correlationId, () -> {
            String status = customerStatus(customerId);
            if ("FROZEN".equals(status)) {
                return true;
            }
            if (!"ACTIVE".equals(status)) {
                return false;
            }
            patchStatus(customerId, "FROZEN", reason, "svc:orchestrator-compliance");
            log.info("compliance.frozen customerId={} reason={}", customerId, reason);
            return true;
        });
    }

    @Override
    public int setCardStatus(String customerId, String correlationId, String status) {
        return withCorrelation(correlationId, () -> {
            JsonNode graph = call("mapping.customers.graph", () -> mapping.get()
                    .uri("/v1/customers/{id}/graph", customerId).retrieve().body(JsonNode.class));
            int changed = 0;
            for (JsonNode ref : graph.path("refs")) {
                if (!"CARD".equals(ref.path("entityType").asText())) {
                    continue;
                }
                String token = ref.path("externalId").asText();
                call("thredd.cards.status", () -> thredd.post().uri("/v1/cards/{token}/status", token)
                        .header("Idempotency-Key", status + ":" + correlationId + ":" + token)
                        .body(Map.of("status", status)).retrieve().toBodilessEntity());
                changed++;
            }
            log.info("compliance.cards customerId={} status={} cards={}", customerId, status, changed);
            return changed;
        });
    }

    @Override
    public void decideCustomer(String customerId, String correlationId, String status, String reviewer, String note) {
        withCorrelation(correlationId, () -> {
            if (!status.equals(customerStatus(customerId))) {
                patchStatus(customerId, status, "Compliance review: " + note, "user:" + reviewer);
            }
            return null;
        });
    }

    @Override
    public int releaseFlaggedPayments(String customerId, String correlationId, String reviewer) {
        return withCorrelation(correlationId, () -> {
            List<Map<String, Object>> flagged = jdbc.sql("""
                            UPDATE payment SET status = 'COMPLETED'
                            WHERE customer_id = :customerId AND status = 'FLAGGED'
                            RETURNING payment_id::text AS "paymentId", amount::text AS amount,
                                      correlation_id AS "correlationId\"""")
                    .param("customerId", UUID.fromString(customerId))
                    .query()
                    .listOfRows();
            for (Map<String, Object> p : flagged) {
                jdbc.sql("""
                                INSERT INTO payment_status_history (payment_id, from_status, to_status, reason, actor,
                                                                    correlation_id)
                                VALUES (CAST(:id AS uuid), 'FLAGGED', 'COMPLETED', 'Released after compliance review',
                                        :actor, :correlationId)""")
                        .param("id", p.get("paymentId"))
                        .param("actor", "user:" + reviewer)
                        .param("correlationId", p.get("correlationId"))
                        .update();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("paymentId", p.get("paymentId"));
                data.put("amount", Map.of("value", String.valueOf(p.get("amount")), "currency", "GBP"));
                data.put("status", "COMPLETED");
                data.put("reason", "Released after compliance review");
                events.publish(Topics.PAYMENT_EVENTS, EventEnvelope.of("payment.released", "orchestrator",
                        String.valueOf(p.get("correlationId")), null, customerId, data));
            }
            return flagged.size();
        });
    }

    @Override
    public void finishSaga(String sagaId, String step, String status) {
        jdbc.sql("UPDATE saga SET current_step = :step, status = :status, updated_at = now() WHERE saga_id = :id")
                .param("step", step).param("status", status).param("id", UUID.fromString(sagaId))
                .update();
    }

    private String customerStatus(String customerId) {
        JsonNode customer = call("mapping.customers.get", () -> mapping.get()
                .uri("/v1/customers/{id}", customerId).retrieve().body(JsonNode.class));
        return customer.path("status").asText();
    }

    private void patchStatus(String customerId, String status, String reason, String actor) {
        call("mapping.customers.status", () -> mapping.patch().uri("/v1/customers/{id}/status", customerId)
                .header("X-Actor", actor)
                .body(Map.of("status", status, "reason", reason)).retrieve().toBodilessEntity());
    }
}
