package bank.pbp.orchestrator.schedules;

import static bank.pbp.orchestrator.temporal.ActivitySupport.withCorrelation;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import bank.pbp.orchestrator.payments.PaymentCommand;
import bank.pbp.orchestrator.payments.PaymentCommandListener;
import bank.pbp.orchestrator.schedules.ScheduleWorkflow.ScheduleRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ScheduleActivitiesImpl implements ScheduleWorkflow.ScheduleActivities {

    private static final Logger log = LoggerFactory.getLogger(ScheduleActivitiesImpl.class);

    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final String bankSortCode;

    public ScheduleActivitiesImpl(JdbcClient jdbc, EventPublisher events, ObjectMapper mapper,
                                  @Value("${pbp.bank.sort-code}") String bankSortCode) {
        this.jdbc = jdbc;
        this.events = events;
        this.mapper = mapper;
        this.bankSortCode = bankSortCode;
    }

    @Override
    public void create(ScheduleRequest r) {
        withCorrelation(r.correlationId(), () -> {
            int inserted = jdbc.sql("""
                            INSERT INTO payment_schedule (schedule_id, customer_id, correlation_id, kind, frequency,
                                   debit_account, creditor, amount, currency, reference, scheme, cop_result,
                                   start_date, end_date, next_date, status)
                            VALUES (:id, :customer, :correlation, :kind, :frequency, :debit, CAST(:creditor AS jsonb),
                                    :amount, :currency, :reference, :scheme, :cop, :start, :end, :start, 'ACTIVE')
                            ON CONFLICT (schedule_id) DO NOTHING""")
                    .param("id", UUID.fromString(r.scheduleId()))
                    .param("customer", UUID.fromString(r.customerId()))
                    .param("correlation", r.correlationId())
                    .param("kind", r.kind())
                    .param("frequency", r.frequency())
                    .param("debit", r.debtorAccount())
                    .param("creditor", json(Map.of("name", r.creditorName(), "sortCode", r.creditorSortCode(),
                            "accountNumber", r.creditorAccountNumber())))
                    .param("amount", new BigDecimal(r.amount()))
                    .param("currency", r.currency())
                    .param("reference", r.reference())
                    .param("scheme", r.scheme())
                    .param("cop", r.copResult())
                    .param("start", LocalDate.parse(r.startDate()))
                    .param("end", r.endDate() == null || r.endDate().isBlank() ? null : LocalDate.parse(r.endDate()))
                    .update();
            if (inserted == 1) {
                log.info("schedule.created scheduleId={} kind={} frequency={} start={}", r.scheduleId(), r.kind(),
                        r.frequency(), r.startDate());
                publish(r, "schedule.created", "ACTIVE", r.startDate(), null);
            }
            return null;
        });
    }

    @Override
    public PaymentCommand prepareRun(ScheduleRequest r, int index) {
        // Each run is its own payment journey, with its own correlation ID.
        String correlationId = Ids.newCorrelationId(JourneyType.PAY);
        return new PaymentCommand(
                Ids.uuidV7().toString(),
                r.customerId(),
                correlationId,
                r.debtorAccount(),
                r.creditorName(),
                r.creditorSortCode(),
                r.creditorAccountNumber(),
                r.amount(),
                r.currency(),
                r.reference(),
                PaymentCommandListener.paymentType(bankSortCode, r.creditorSortCode(), r.scheme()),
                "schedule:" + r.scheduleId() + ":" + index,
                Instant.now().truncatedTo(ChronoUnit.MICROS).toString(),
                r.copResult(),
                r.scheduleId());
    }

    @Override
    public void recordRun(ScheduleRequest r, int index, String paymentId, String paymentStatus, String nextDate) {
        withCorrelation(r.correlationId(), () -> {
            jdbc.sql("""
                            UPDATE payment_schedule
                            SET payments_made = GREATEST(payments_made, :made), last_payment_id = :payment,
                                last_status = :status, next_date = :next, updated_at = now()
                            WHERE schedule_id = :id""")
                    .param("made", index + 1)
                    .param("payment", UUID.fromString(paymentId))
                    .param("status", paymentStatus)
                    .param("next", nextDate == null ? null : LocalDate.parse(nextDate))
                    .param("id", UUID.fromString(r.scheduleId()))
                    .update();
            log.info("schedule.run scheduleId={} run={} paymentId={} status={} next={}", r.scheduleId(), index + 1,
                    paymentId, paymentStatus, nextDate);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("paymentId", paymentId);
            extra.put("paymentStatus", paymentStatus);
            publish(r, "schedule.payment_made", "ACTIVE", nextDate, extra);
            return null;
        });
    }

    @Override
    public void finish(ScheduleRequest r, String status, String reason) {
        withCorrelation(r.correlationId(), () -> {
            int rows = jdbc.sql("""
                            UPDATE payment_schedule SET status = :status, next_date = NULL, updated_at = now()
                            WHERE schedule_id = :id AND status = 'ACTIVE'""")
                    .param("status", status)
                    .param("id", UUID.fromString(r.scheduleId()))
                    .update();
            if (rows == 1) {
                log.info("schedule.{} scheduleId={} reason={}", status.toLowerCase(), r.scheduleId(), reason);
                publish(r, "schedule." + status.toLowerCase(), status, null, Map.of("reason", reason));
            }
            return null;
        });
    }

    private void publish(ScheduleRequest r, String type, String status, String nextDate, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scheduleId", r.scheduleId());
        data.put("kind", r.kind());
        data.put("frequency", r.frequency());
        data.put("creditorName", r.creditorName());
        data.put("amount", Map.of("value", r.amount(), "currency", r.currency()));
        data.put("status", status);
        data.put("nextDate", nextDate);
        if (extra != null) {
            data.putAll(extra);
        }
        events.publish(Topics.PAYMENT_EVENTS,
                EventEnvelope.of(type, "orchestrator", r.correlationId(), null, r.customerId(), data));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
