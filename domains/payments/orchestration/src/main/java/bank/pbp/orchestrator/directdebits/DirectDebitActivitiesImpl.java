package bank.pbp.orchestrator.directdebits;

import static bank.pbp.orchestrator.temporal.ActivitySupport.call;
import static bank.pbp.orchestrator.temporal.ActivitySupport.withCorrelation;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.Claim;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.Collection;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.Eligibility;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.MandateNotice;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.Standing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Component
public class DirectDebitActivitiesImpl implements DirectDebitWorkflows.DirectDebitActivities {

    private static final Logger log = LoggerFactory.getLogger(DirectDebitActivitiesImpl.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final RestClient mapping;
    private final RestClient essence;

    public DirectDebitActivitiesImpl(JdbcClient jdbc, TransactionTemplate tx, EventPublisher events, ObjectMapper mapper,
                                     @Qualifier("mappingClient") RestClient mapping,
                                     @Qualifier("essenceClient") RestClient essence) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.mapper = mapper;
        this.mapping = mapping;
        this.essence = essence;
    }

    // ---------- mandates ----------

    @Override
    public String accountOwner(String correlationId, String account) {
        return withCorrelation(correlationId, () -> owner(account).path("customerId").asText());
    }

    @Override
    public void recordMandate(MandateNotice n, String customerId) {
        withCorrelation(n.correlationId(), () -> {
            int inserted = jdbc.sql("""
                            INSERT INTO dd_mandate (mandate_id, mandate_ref, customer_id, debit_account, originator_name,
                                                    service_user_number, payer_reference, status, correlation_id)
                            VALUES (:id, :ref, :customer, :account, :originator, :sun, :payerRef, 'ACTIVE', :correlation)
                            ON CONFLICT (mandate_ref) DO NOTHING""")
                    .param("id", UUID.fromString(n.mandateId()))
                    .param("ref", n.mandateRef())
                    .param("customer", UUID.fromString(customerId))
                    .param("account", n.account())
                    .param("originator", n.originatorName())
                    .param("sun", n.serviceUserNumber())
                    .param("payerRef", n.payerReference())
                    .param("correlation", n.correlationId())
                    .update();
            if (inserted == 1) {
                log.info("directdebit.mandate_created mandateRef={} originator={} customerId={}", n.mandateRef(),
                        n.originatorName(), customerId);
                publishMandate(n, customerId, "directdebit.mandate_created", "ACTIVE", null);
            }
            return null;
        });
    }

    @Override
    public void cancelMandate(MandateNotice n, String customerId, String cancelledBy) {
        withCorrelation(n.correlationId(), () -> {
            call("essence.directdebits.cancel", () -> essence.post()
                    .uri("/v1/direct-debits/mandates/{ref}/cancel", n.mandateRef())
                    .body(Map.of("reasonCode", "1"))
                    .retrieve().toBodilessEntity());
            int rows = jdbc.sql("""
                            UPDATE dd_mandate SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = :by
                            WHERE mandate_ref = :ref AND status = 'ACTIVE'""")
                    .param("by", cancelledBy)
                    .param("ref", n.mandateRef())
                    .update();
            if (rows == 1) {
                log.info("directdebit.mandate_cancelled mandateRef={} by={}", n.mandateRef(), cancelledBy);
                publishMandate(n, customerId, "directdebit.mandate_cancelled", "CANCELLED", cancelledBy);
            }
            return null;
        });
    }

    // ---------- collections ----------

    @Override
    public Standing present(Collection c) {
        return withCorrelation(c.correlationId(), () -> {
            Standing s = standingNow(c);
            tx.execute(status -> {
                Map<String, String> creditor = new LinkedHashMap<>();
                creditor.put("name", c.originatorName());
                creditor.put("serviceUserNumber", c.serviceUserNumber());
                creditor.put("mandateRef", c.mandateRef());
                creditor.put("payerReference", c.payerReference());
                creditor.put("dueDate", c.dueDate());
                int inserted = jdbc.sql("""
                                INSERT INTO payment (payment_id, correlation_id, customer_id, debit_account, creditor,
                                                     amount, currency, payment_type, status, finastra_ref,
                                                     idempotency_key, created_at)
                                VALUES (:id, :correlation, :customer, :debit, CAST(:creditor AS jsonb), :amount,
                                        :currency, 'DIRECT_DEBIT', 'PRESENTED', :ref, :idem, :createdAt)
                                ON CONFLICT DO NOTHING""")
                        .param("id", UUID.fromString(c.paymentId()))
                        .param("correlation", c.correlationId())
                        .param("customer", UUID.fromString(s.customerId()))
                        .param("debit", c.account())
                        .param("creditor", json(creditor))
                        .param("amount", new BigDecimal(c.amount()))
                        .param("currency", c.currency())
                        .param("ref", c.collectionRef())
                        .param("idem", "bacs:" + c.collectionRef())
                        .param("createdAt", OffsetDateTime.parse(c.receivedAt()))
                        .update();
                if (inserted == 1) {
                    jdbc.sql("""
                                    INSERT INTO payment_status_history (payment_id, from_status, to_status, reason, actor,
                                                                        correlation_id)
                                    VALUES (:id, NULL, 'PRESENTED', :reason, 'vendor:bacs', :correlation)""")
                            .param("id", UUID.fromString(c.paymentId()))
                            .param("reason", "Due " + c.dueDate() + " · mandate " + c.mandateRef())
                            .param("correlation", c.correlationId())
                            .update();
                }
                return null;
            });
            publishCollection(c, s.customerId(), "payment.presented", "PRESENTED", "Due " + c.dueDate());
            log.info("directdebit.presented collectionRef={} amount={} due={} mandate={}", c.collectionRef(), c.amount(),
                    c.dueDate(), s.mandateStatus());
            return s;
        });
    }

    @Override
    public Standing standing(Collection c) {
        return withCorrelation(c.correlationId(), () -> standingNow(c));
    }

    @Override
    public void pay(Collection c) {
        withCorrelation(c.correlationId(), () -> call("essence.directdebits.pay", () -> essence.post()
                .uri("/v1/direct-debits/collections/{ref}/pay", c.collectionRef())
                .retrieve().toBodilessEntity()));
    }

    @Override
    public void returnUnpaid(Collection c, String reasonCode) {
        withCorrelation(c.correlationId(), () -> call("essence.directdebits.return", () -> essence.post()
                .uri("/v1/direct-debits/collections/{ref}/return", c.collectionRef())
                .body(Map.of("reasonCode", reasonCode))
                .retrieve().toBodilessEntity()));
    }

    @Override
    public void settleCollection(Collection c, String customerId, String status, String reason) {
        boolean moved = withCorrelation(c.correlationId(), () -> move(UUID.fromString(c.paymentId()),
                OffsetDateTime.parse(c.receivedAt()), "PRESENTED", status, reason, "svc:orchestrator", c.correlationId()));
        if (moved) {
            log.info("directdebit.{} collectionRef={} reason={}", status.toLowerCase(), c.collectionRef(), reason);
            withCorrelation(c.correlationId(), () -> {
                publishCollection(c, customerId, "payment." + status.toLowerCase(), status, reason);
                return null;
            });
        }
    }

    // ---------- Direct Debit Guarantee ----------

    @Override
    public Eligibility claimable(Claim claim) {
        return withCorrelation(claim.correlationId(), () -> {
            Optional<Map<String, Object>> row = jdbc.sql("""
                            SELECT customer_id::text AS customer, payment_type AS type, status, finastra_ref AS ref
                            FROM payment WHERE payment_id = :id""")
                    .param("id", UUID.fromString(claim.paymentId()))
                    .query().listOfRows().stream().findFirst();
            if (row.isEmpty() || !claim.customerId().equals(row.get().get("customer"))) {
                return new Eligibility(false, "PAY-DD-002: no such Direct Debit on your account", null);
            }
            Map<String, Object> p = row.get();
            if (!"DIRECT_DEBIT".equals(p.get("type"))) {
                return new Eligibility(false, "PAY-DD-002: only Direct Debits can be claimed under the Guarantee", null);
            }
            if ("REFUNDED".equals(p.get("status"))) {
                return new Eligibility(false, "PAY-DD-002: this Direct Debit has already been refunded", null);
            }
            if (!"COMPLETED".equals(p.get("status"))) {
                return new Eligibility(false, "PAY-DD-002: only a paid Direct Debit can be refunded", null);
            }
            return new Eligibility(true, null, String.valueOf(p.get("ref")));
        });
    }

    @Override
    public void refund(Claim claim, String collectionRef) {
        withCorrelation(claim.correlationId(), () -> {
            JsonNode r = call("essence.directdebits.indemnity", () -> essence.post()
                    .uri("/v1/direct-debits/collections/{ref}/indemnity", collectionRef)
                    .body(Map.of("reasonCode", claim.reasonCode() == null ? "1" : claim.reasonCode()))
                    .retrieve().body(JsonNode.class));
            Map<String, Object> p = jdbc.sql("""
                            SELECT created_at, amount::text AS amount, currency, creditor::text AS creditor,
                                   debit_account AS account
                            FROM payment WHERE payment_id = :id""")
                    .param("id", UUID.fromString(claim.paymentId()))
                    .query().singleRow();
            OffsetDateTime createdAt = (OffsetDateTime) toOffset(p.get("created_at"));
            String claimRef = r == null ? null : r.path("claimRef").asText(null);
            boolean moved = move(UUID.fromString(claim.paymentId()), createdAt, "COMPLETED", "REFUNDED",
                    "Direct Debit Guarantee refund; indemnity claim " + claimRef + " raised on the originator",
                    "customer", claim.correlationId());
            if (moved) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("paymentId", claim.paymentId());
                data.put("type", "DIRECT_DEBIT");
                data.put("amount", Map.of("value", p.get("amount"), "currency", p.get("currency")));
                data.put("debtorAccount", p.get("account"));
                data.put("status", "REFUNDED");
                data.put("claimRef", claimRef);
                events.publish(Topics.PAYMENT_EVENTS, EventEnvelope.of("payment.refunded", "orchestrator",
                        claim.correlationId(), null, claim.customerId(), data));
                log.info("directdebit.refunded paymentId={} claimRef={}", claim.paymentId(), claimRef);
            }
            return null;
        });
    }

    @Override
    public void claimRefused(Claim claim, String reason) {
        withCorrelation(claim.correlationId(), () -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("paymentId", claim.paymentId());
            data.put("reason", reason);
            events.publish(Topics.PAYMENT_EVENTS, EventEnvelope.of("directdebit.claim_refused", "orchestrator",
                    claim.correlationId(), null, claim.customerId(), data));
            log.info("directdebit.claim_refused paymentId={} reason={}", claim.paymentId(), reason);
            return null;
        });
    }

    // ---------- helpers ----------

    private Standing standingNow(Collection c) {
        JsonNode customer = owner(c.account());
        String mandateStatus = jdbc.sql("SELECT status FROM dd_mandate WHERE mandate_ref = :ref")
                .param("ref", c.mandateRef()).query(String.class).optional().orElse("NONE");
        return new Standing(customer.path("customerId").asText(), customer.path("status").asText(), mandateStatus);
    }

    private JsonNode owner(String account) {
        JsonNode r = call("mapping.resolve", () -> mapping.get()
                .uri(u -> u.path("/v1/resolve").queryParam("system", "ESSENCE").queryParam("type", "ACCOUNT")
                        .queryParam("id", account).build())
                .retrieve().body(JsonNode.class));
        return r.path("customer");
    }

    private boolean move(UUID paymentId, OffsetDateTime createdAt, String from, String to, String reason, String actor,
                         String correlationId) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            int rows = jdbc.sql("""
                            UPDATE payment SET status = :to
                            WHERE payment_id = :id AND created_at = :createdAt AND status = :from""")
                    .param("to", to).param("id", paymentId).param("createdAt", createdAt).param("from", from)
                    .update();
            if (rows == 0) {
                return false;
            }
            jdbc.sql("""
                            INSERT INTO payment_status_history (payment_id, from_status, to_status, reason, actor,
                                                                correlation_id)
                            VALUES (:id, :from, :to, :reason, :actor, :correlation)""")
                    .param("id", paymentId).param("from", from).param("to", to).param("reason", reason)
                    .param("actor", actor).param("correlation", correlationId)
                    .update();
            return true;
        }));
    }

    private static Object toOffset(Object value) {
        if (value instanceof OffsetDateTime o) {
            return o;
        }
        if (value instanceof java.sql.Timestamp t) {
            return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(String.valueOf(value));
    }

    private void publishMandate(MandateNotice n, String customerId, String type, String status, String by) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mandateId", n.mandateId());
        data.put("mandateRef", n.mandateRef());
        data.put("originatorName", n.originatorName());
        data.put("serviceUserNumber", n.serviceUserNumber());
        data.put("account", n.account());
        data.put("status", status);
        data.put("cancelledBy", by);
        events.publish(Topics.PAYMENT_EVENTS, EventEnvelope.of(type, "orchestrator", n.correlationId(), null, customerId, data));
    }

    private void publishCollection(Collection c, String customerId, String type, String status, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", c.paymentId());
        data.put("type", "DIRECT_DEBIT");
        data.put("collectionRef", c.collectionRef());
        data.put("mandateRef", c.mandateRef());
        data.put("originatorName", c.originatorName());
        data.put("debtorAccount", c.account());
        data.put("amount", Map.of("value", c.amount(), "currency", c.currency()));
        data.put("dueDate", c.dueDate());
        data.put("status", status);
        data.put("reason", reason);
        events.publish(Topics.PAYMENT_EVENTS, EventEnvelope.of(type, "orchestrator", c.correlationId(), null, customerId, data));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
