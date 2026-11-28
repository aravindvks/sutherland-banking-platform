package bank.pbp.orchestrator.payments;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.market.MarketPack;
import bank.pbp.common.market.PaymentScheme;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PaymentActivitiesImpl implements PaymentActivities {

    private static final Logger log = LoggerFactory.getLogger(PaymentActivitiesImpl.class);
    private static final Set<String> FINAL = Set.of("COMPLETED", "REJECTED", "FAILED", "RETURNED");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final RestClient essence;
    private final BigDecimal singleLimit;
    private final BigDecimal reviewThreshold;
    private final MarketPack market;
    private final PaymentScheme chaps;
    private final String currency;

    public PaymentActivitiesImpl(JdbcClient jdbc, TransactionTemplate tx, EventPublisher events, ObjectMapper mapper,
                                 RestClient.Builder builder,
                                 @Value("${pbp.adapters.essence-url}") String essenceUrl,
                                 MarketPack market) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000);
        f.setReadTimeout(12_000);
        this.essence = builder.clone().baseUrl(essenceUrl).requestFactory(f).build();
        // Limits, fees and the CHAPS hours come from the market pack (docs/design/market-packs.md, Stage 1 steps 2
        // and 5); the UK pack reads the same pbp.payments.* keys these fields were read from before.
        this.market = market;
        this.chaps = market.paymentScheme("CHAPS").orElseThrow(
                () -> new IllegalStateException("market pack '" + market.code() + "' has no CHAPS scheme"));
        this.currency = market.currency().getCurrencyCode();
        this.singleLimit = market.limits().singlePayment();
        this.reviewThreshold = market.limits().reviewThreshold();
    }

    @Override
    public void createPayment(PaymentCommand c) {
        withCorrelation(c, () -> tx.execute(status -> {
            Map<String, String> creditor = Map.of("name", c.creditorName(), "sortCode", c.creditorSortCode(),
                    "accountNumber", c.creditorAccountNumber());
            int inserted = jdbc.sql("""
                            INSERT INTO payment (payment_id, correlation_id, customer_id, debit_account, creditor,
                                                 amount, currency, payment_type, status, idempotency_key, created_at,
                                                 schedule_id, creditor_account)
                            VALUES (:id, :correlationId, :customerId, :debit, CAST(:creditor AS jsonb), :amount,
                                    :currency, :type, 'INITIATED', :idem, :createdAt, CAST(:schedule AS uuid),
                                    CAST(:account AS jsonb))
                            ON CONFLICT DO NOTHING""")
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("correlationId", c.correlationId())
                    .param("customerId", UUID.fromString(c.customerId()))
                    .param("debit", c.debtorAccount())
                    .param("creditor", json(creditor))
                    .param("account", c.account() == null ? null : json(c.account().toJson()))
                    .param("amount", new BigDecimal(c.amount()))
                    .param("currency", c.currency())
                    .param("type", c.paymentType())
                    .param("idem", c.idempotencyKey())
                    .param("createdAt", OffsetDateTime.parse(c.initiatedAt()))
                    .param("schedule", c.scheduleId())
                    .update();
            if (inserted == 1) {
                jdbc.sql("""
                                INSERT INTO saga (saga_id, saga_type, customer_id, correlation_id, current_step, status)
                                VALUES (:id, 'PAYMENT', :customerId, :correlationId, 'INITIATED', 'RUNNING')
                                ON CONFLICT DO NOTHING""")
                        .param("id", UUID.fromString(c.paymentId()))
                        .param("customerId", UUID.fromString(c.customerId()))
                        .param("correlationId", c.correlationId())
                        .update();
                jdbc.sql("""
                                INSERT INTO payment_status_history (payment_id, from_status, to_status, actor, correlation_id)
                                VALUES (:id, NULL, 'INITIATED', 'svc:orchestrator', :correlationId)""")
                        .param("id", UUID.fromString(c.paymentId()))
                        .param("correlationId", c.correlationId())
                        .update();
                log.info("payment.initiated paymentId={} type={} amount={} {}", c.paymentId(), c.paymentType(),
                        c.amount(), c.currency());
            }
            return null;
        }));
        publish(c, "payment.initiated", "INITIATED", null);
    }

    @Override
    public RiskResult riskCheck(PaymentCommand c) {
        return withCorrelation(c, () -> {
            BigDecimal amount = new BigDecimal(c.amount());
            if (amount.signum() <= 0) {
                return RiskResult.fail("GEN-VALID-001", "Amount must be greater than zero");
            }
            // The routed scheme's own limit and fee. In the UK: same-bank and Faster Payments share the
            // single-payment limit and are free; CHAPS has its own limit and fee.
            PaymentScheme scheme = market.paymentScheme(c.paymentType()).orElse(null);
            BigDecimal limit = scheme == null ? singleLimit : scheme.limit().orElse(singleLimit);
            String limitName = scheme == null || scheme.instant() ? "single-payment" : scheme.name();
            if (amount.compareTo(limit) > 0) {
                return RiskResult.fail("PAY-LIMIT-001", "Requested " + c.amount() + " " + currency + " exceeds the "
                        + limitName + " limit " + limit.toPlainString() + " " + currency);
            }
            boolean review = amount.compareTo(reviewThreshold) >= 0;
            return RiskResult.pass(review, scheme == null ? "0.00" : scheme.fee().setScale(2).toPlainString());
        });
    }

    @Override
    public CopCheck confirmPayee(PaymentCommand c) {
        return withCorrelation(c, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", c.creditorName());
            body.put("sortCode", c.creditorSortCode());
            body.put("accountNumber", c.creditorAccountNumber());
            Map<String, Object> r;
            try {
                r = essence.post().uri("/v1/payees/confirm").body(body).retrieve().body(MAP);
            } catch (RestClientResponseException e) {
                throw ApplicationFailure.newFailure("Confirmation of Payee failed: " + e.getStatusCode(), "PAY-COP-002");
            }
            String result = r == null || r.get("result") == null ? "UNAVAILABLE" : String.valueOf(r.get("result"));
            jdbc.sql("UPDATE payment SET cop_result = :r, name_check_result = :r WHERE payment_id = :id AND created_at = :createdAt")
                    .param("r", result)
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("createdAt", OffsetDateTime.parse(c.initiatedAt()))
                    .update();
            log.info("payment.cop paymentId={} result={}", c.paymentId(), result);
            io.micrometer.core.instrument.Metrics.counter("pbp.cop.results", "result",
                    bank.pbp.common.observability.FlowMetrics.label(result),
                    "type", bank.pbp.common.observability.FlowMetrics.label(c.paymentType())).increment();
            return new CopCheck(result, r == null ? null : (String) r.get("matchedName"),
                    r == null ? null : (String) r.get("reasonCode"));
        });
    }

    @Override
    public Window chapsWindow() {
        Instant now = Instant.now();
        Instant opens = chaps.window().nextOpening(now, market.zone(), market.calendar());
        return new Window(chaps.window().isOpen(now, market.zone(), market.calendar()),
                opens.atZone(market.zone()).toOffsetDateTime().toString(),
                Math.max(0, opens.toEpochMilli() - now.toEpochMilli()));
    }

    @Override
    public String chargeFee(PaymentCommand c, String fee) {
        return withCorrelation(c, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", Map.of("value", fee, "currency", c.currency()));
            // Same reference as the payment, so reconciliation sees payment and fee together.
            body.put("reference", c.paymentId());
            Map<String, Object> r;
            try {
                r = essence.post().uri("/v1/accounts/{acc}/fees", c.debtorAccount())
                        .header("Idempotency-Key", "fee:" + c.paymentId())
                        .body(body).retrieve().body(MAP);
            } catch (RestClientResponseException e) {
                throw ApplicationFailure.newFailure("Fee not posted: " + e.getStatusCode(), "VND-REJECT-001");
            }
            jdbc.sql("UPDATE payment SET fee = :fee WHERE payment_id = :id AND created_at = :createdAt")
                    .param("fee", new BigDecimal(fee))
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("createdAt", OffsetDateTime.parse(c.initiatedAt()))
                    .update();
            log.info("payment.fee paymentId={} fee={}", c.paymentId(), fee);
            return String.valueOf(r == null ? null : r.get("postingRef"));
        });
    }

    @Override
    public void transition(PaymentCommand c, String from, String to, String reason) {
        boolean moved = withCorrelation(c, () -> Boolean.TRUE.equals(tx.execute(status -> {
            int rows = jdbc.sql("""
                            UPDATE payment SET status = :to
                            WHERE payment_id = :id AND created_at = :createdAt AND status = :from""")
                    .param("to", to)
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("createdAt", OffsetDateTime.parse(c.initiatedAt()))
                    .param("from", from)
                    .update();
            if (rows == 0) {
                return false;
            }
            jdbc.sql("""
                            INSERT INTO payment_status_history (payment_id, from_status, to_status, reason, actor,
                                                                correlation_id)
                            VALUES (:id, :from, :to, :reason, 'svc:orchestrator', :correlationId)""")
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("from", from)
                    .param("to", to)
                    .param("reason", reason)
                    .param("correlationId", c.correlationId())
                    .update();
            jdbc.sql("""
                            UPDATE saga SET current_step = :to, updated_at = now(),
                                   status = CASE WHEN :final THEN (CASE WHEN :to = 'COMPLETED' THEN 'COMPLETED' ELSE 'FAILED' END)
                                                 ELSE status END
                            WHERE saga_id = :id""")
                    .param("to", to)
                    .param("final", FINAL.contains(to))
                    .param("id", UUID.fromString(c.paymentId()))
                    .update();
            return true;
        })));
        if (moved) {
            log.info("payment.status paymentId={} {} -> {} reason={}", c.paymentId(), from, to, reason);
            publish(c, "payment." + to.toLowerCase(), to, reason);
        }
    }

    @Override
    public String postInternalTransfer(PaymentCommand c) {
        return withCorrelation(c, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("debitAccount", c.debtorAccount());
            body.put("creditor", Map.of("name", c.creditorName(), "sortCode", c.creditorSortCode(),
                    "accountNumber", c.creditorAccountNumber()));
            body.put("amount", Map.of("value", c.amount(), "currency", c.currency()));
            body.put("reference", c.remittanceInfo());
            body.put("idempotencyKey", c.paymentId());
            try {
                Map<String, Object> result = essence.post()
                        .uri("/v1/transfers")
                        .body(body)
                        .retrieve()
                        .body(MAP);
                return String.valueOf(result == null ? null : result.get("postingRef"));
            } catch (RestClientResponseException e) {
                // The adapter answers with the LLD problem envelope; its retryable flag decides.
                JsonNode problem = problem(e.getResponseBodyAsString());
                String code = problem.path("code").asText("VND-REJECT-001");
                String detail = problem.path("detail").asText("Rejected by Essence");
                boolean retryable = problem.path("retryable").asBoolean(e.getStatusCode().is5xxServerError());
                if (!retryable) {
                    throw ApplicationFailure.newNonRetryableFailure(detail, code);
                }
                throw ApplicationFailure.newFailure(detail, code);
            }
        });
    }

    @Override
    public String submitPayment(PaymentCommand c) {
        return withCorrelation(c, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("debitAccount", c.debtorAccount());
            body.put("creditor", Map.of("name", c.creditorName(), "sortCode", c.creditorSortCode(),
                    "accountNumber", c.creditorAccountNumber()));
            body.put("amount", Map.of("value", c.amount(), "currency", c.currency()));
            body.put("reference", c.remittanceInfo());
            // Our payment ID travels as the end-to-end ID, so Finastra's status reports name the payment.
            body.put("endToEndId", c.paymentId());
            body.put("idempotencyKey", c.paymentId());
            body.put("scheme", "CHAPS".equals(c.paymentType()) ? "CHAPS" : "FPS");
            Map<String, Object> result;
            try {
                result = essence.post().uri("/v1/payments").body(body).retrieve().body(MAP);
            } catch (RestClientResponseException e) {
                JsonNode problem = problem(e.getResponseBodyAsString());
                String code = problem.path("code").asText("VND-REJECT-001");
                String detail = problem.path("detail").asText("Rejected by Finastra");
                if (!problem.path("retryable").asBoolean(e.getStatusCode().is5xxServerError())) {
                    throw ApplicationFailure.newNonRetryableFailure(detail, code);
                }
                throw ApplicationFailure.newFailure(detail, code);
            }
            String finastraRef = String.valueOf(result == null ? null : result.get("finastraRef"));
            jdbc.sql("""
                            UPDATE payment SET finastra_ref = :ref
                            WHERE payment_id = :id AND created_at = :createdAt""")
                    .param("ref", finastraRef)
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("createdAt", OffsetDateTime.parse(c.initiatedAt()))
                    .update();
            log.info("payment.submitted paymentId={} finastraRef={} scheme={}", c.paymentId(), finastraRef,
                    result == null ? null : result.get("scheme"));
            return finastraRef;
        });
    }

    private void publish(PaymentCommand c, String type, String status, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", c.paymentId());
        data.put("debtorAccount", c.debtorAccount());
        data.put("amount", Map.of("value", c.amount(), "currency", c.currency()));
        data.put("type", c.paymentType());
        data.put("status", status);
        data.put("reason", reason);
        withCorrelation(c, () -> {
            events.publish(Topics.PAYMENT_EVENTS,
                    EventEnvelope.of(type, "orchestrator", c.correlationId(), null, c.customerId(), data));
            return null;
        });
    }

    private JsonNode problem(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Activities run on Temporal threads, so the correlation ID is restored explicitly for logs and calls. */
    private static <T> T withCorrelation(PaymentCommand c, Supplier<T> work) {
        Correlation.set(c.correlationId());
        try {
            return work.get();
        } finally {
            Correlation.clear();
        }
    }
}
