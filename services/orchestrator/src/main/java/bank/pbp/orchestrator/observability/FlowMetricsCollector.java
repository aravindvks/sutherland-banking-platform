package bank.pbp.orchestrator.observability;

import bank.pbp.common.observability.FlowMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns the orchestration tables into the flow metrics behind the dashboards and the "stuck" alerts
 * (LLD section 15). Reading our own tables means a payment that silently stops moving still shows up,
 * without a metric call in every workflow (workflow code must stay deterministic).
 *
 * <ul>
 *   <li>{@code pbp_payment_transitions_total{type,status,reason}}: every status change. Counted from
 *       payment_status_history behind a shared watermark row, so with several replicas each change is
 *       counted once; sum across instances.</li>
 *   <li>{@code pbp_payment_completion_seconds{type}}: from initiation to COMPLETED.</li>
 *   <li>{@code pbp_payments_open} / {@code pbp_payments_oldest_seconds{type,status}}: open payments and
 *       how long the oldest has been in its current status. Same value on every replica; use max.</li>
 *   <li>{@code pbp_sagas_open} / {@code pbp_sagas_oldest_seconds{saga,step}}: onboarding, payment and
 *       compliance sagas still RUNNING or WAITING.</li>
 *   <li>{@code pbp_schedules_active} / {@code pbp_schedules_overdue{kind}}, {@code pbp_dd_mandates{status}}.</li>
 * </ul>
 */
@Component
public class FlowMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(FlowMetricsCollector.class);

    /** Statuses after which a payment no longer moves. Everything else counts as open. */
    static final List<String> TERMINAL = List.of("COMPLETED", "FAILED", "RETURNED", "REJECTED", "REFUNDED", "CANCELLED");

    /** Rows are counted only once this old, so transactions still committing are not skipped. */
    private static final Duration SETTLE = Duration.ofSeconds(5);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MeterRegistry registry;
    private final MultiGauge paymentsOpen;
    private final MultiGauge paymentsOldest;
    private final MultiGauge sagasOpen;
    private final MultiGauge sagasOldest;
    private final MultiGauge schedulesActive;
    private final MultiGauge schedulesOverdue;
    private final MultiGauge mandates;
    private final MultiGauge creditApplications;
    private final MultiGauge creditApplicationsOldest;
    private final MultiGauge creditAccounts;

    private final String zone;

    public FlowMetricsCollector(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry registry,
                                bank.pbp.common.market.MarketPack market) {
        this.zone = market.zone().getId();   // the market's local time for "overdue" (docs/design/market-packs.md)
        this.jdbc = jdbc;
        this.tx = tx;
        this.registry = registry;
        this.paymentsOpen = MultiGauge.builder("pbp.payments.open").description("Open payments by type and status")
                .register(registry);
        this.paymentsOldest = MultiGauge.builder("pbp.payments.oldest").baseUnit("seconds")
                .description("Age of the oldest open payment in its current status").register(registry);
        this.sagasOpen = MultiGauge.builder("pbp.sagas.open").register(registry);
        this.sagasOldest = MultiGauge.builder("pbp.sagas.oldest").baseUnit("seconds").register(registry);
        this.schedulesActive = MultiGauge.builder("pbp.schedules.active").register(registry);
        this.schedulesOverdue = MultiGauge.builder("pbp.schedules.overdue")
                .description("Active schedules whose payment is due and not yet made (06:30 UK grace)")
                .register(registry);
        this.mandates = MultiGauge.builder("pbp.dd.mandates").register(registry);
        this.creditApplications = MultiGauge.builder("pbp.credit.applications.open")
                .description("Credit card applications not yet decided or accepted").register(registry);
        this.creditApplicationsOldest = MultiGauge.builder("pbp.credit.applications.oldest").baseUnit("seconds")
                .register(registry);
        this.creditAccounts = MultiGauge.builder("pbp.credit.accounts").register(registry);
    }

    @Scheduled(initialDelayString = "${pbp.observability.collect-initial-ms:10000}",
            fixedDelayString = "${pbp.observability.collect-ms:15000}")
    public void collect() {
        try {
            countTransitions();
            snapshot();
        } catch (RuntimeException e) {
            // Metrics must never take the orchestrator down; the gap shows on the dashboards.
            log.warn("flow.metrics.failed error={}", e.toString());
        }
    }

    /** Counts status changes since the shared watermark, then moves it, in one transaction. */
    void countTransitions() {
        tx.executeWithoutResult(status -> {
            Optional<OffsetDateTime> from = jdbc.sql("""
                            SELECT at FROM flow_metrics_watermark WHERE name = 'payment_status_history'
                            FOR UPDATE SKIP LOCKED""")
                    .query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).optional();
            if (from.isEmpty()) {
                return; // another replica is counting this window
            }
            OffsetDateTime to = jdbc.sql("SELECT now()")
                    .query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).single().minus(SETTLE);
            if (!to.isAfter(from.get())) {
                return;
            }
            jdbc.sql("""
                            SELECT p.payment_type, h.to_status, h.reason, count(*) AS n
                            FROM payment_status_history h JOIN payment p ON p.payment_id = h.payment_id
                            WHERE h.at > :from AND h.at <= :to
                            GROUP BY 1, 2, 3""")
                    .param("from", from.get()).param("to", to)
                    .query((rs, n) -> {
                        registry.counter("pbp.payment.transitions",
                                "type", FlowMetrics.label(rs.getString(1)),
                                "status", FlowMetrics.label(rs.getString(2)),
                                "reason", FlowMetrics.label(rs.getString(3))).increment(rs.getLong(4));
                        return null;
                    }).list();
            jdbc.sql("""
                            SELECT p.payment_type, extract(epoch FROM h.at - p.created_at) AS secs
                            FROM payment_status_history h JOIN payment p ON p.payment_id = h.payment_id
                            WHERE h.to_status = 'COMPLETED' AND h.at > :from AND h.at <= :to""")
                    .param("from", from.get()).param("to", to)
                    .query((rs, n) -> {
                        double secs = rs.getDouble(2);
                        if (secs >= 0) {
                            Timer.builder("pbp.payment.completion").tag("type", FlowMetrics.label(rs.getString(1)))
                                    .register(registry).record(Duration.ofMillis((long) (secs * 1000)));
                        }
                        return null;
                    }).list();
            jdbc.sql("UPDATE flow_metrics_watermark SET at = :to WHERE name = 'payment_status_history'")
                    .param("to", to).update();
        });
    }

    /** Current state: open payments and sagas, schedules and mandates. */
    void snapshot() {
        List<Object[]> payments = jdbc.sql("""
                        SELECT p.payment_type, p.status, count(*) AS n,
                               max(extract(epoch FROM now() - coalesce(h.at, p.created_at))) AS oldest
                        FROM payment p
                        LEFT JOIN LATERAL (SELECT max(at) AS at FROM payment_status_history
                                           WHERE payment_id = p.payment_id) h ON true
                        WHERE p.status NOT IN (:terminal)
                        GROUP BY 1, 2""")
                .param("terminal", TERMINAL)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getLong(3), rs.getDouble(4)})
                .list();
        paymentsOpen.register(rows(payments, 2, "type", "status"), true);
        paymentsOldest.register(rows(payments, 3, "type", "status"), true);

        List<Object[]> sagas = jdbc.sql("""
                        SELECT saga_type, current_step, count(*) AS n, max(extract(epoch FROM now() - updated_at))
                        FROM saga WHERE status IN ('RUNNING', 'WAITING') GROUP BY 1, 2""")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getLong(3), rs.getDouble(4)})
                .list();
        sagasOpen.register(rows(sagas, 2, "saga", "step"), true);
        sagasOldest.register(rows(sagas, 3, "saga", "step"), true);

        schedulesActive.register(rows(jdbc.sql("""
                        SELECT kind, count(*) FROM payment_schedule WHERE status = 'ACTIVE' GROUP BY 1""")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)}).list(), 1, "kind"), true);
        schedulesOverdue.register(rows(jdbc.sql("""
                        SELECT kind, count(*) FROM payment_schedule
                        WHERE status = 'ACTIVE' AND next_date IS NOT NULL
                          AND (next_date < (now() AT TIME ZONE :zone)::date
                               OR (next_date = (now() AT TIME ZONE :zone)::date
                                   AND (now() AT TIME ZONE :zone)::time > time '06:30'))
                        GROUP BY 1""")
                .param("zone", zone)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)}).list(), 1, "kind"), true);
        List<Object[]> applications = jdbc.sql("""
                        SELECT status, count(*), max(extract(epoch FROM now() - updated_at))
                        FROM credit_application WHERE status IN ('RECEIVED', 'REFERRED', 'OFFERED') GROUP BY 1""")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2), rs.getDouble(3)}).list();
        creditApplications.register(rows(applications, 1, "status"), true);
        creditApplicationsOldest.register(rows(applications, 2, "status"), true);
        creditAccounts.register(rows(jdbc.sql("SELECT status, count(*) FROM credit_account GROUP BY 1")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)}).list(), 1, "status"), true);
        mandates.register(rows(jdbc.sql("SELECT status, count(*) FROM dd_mandate GROUP BY 1")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)}).list(), 1, "status"), true);
    }

    /** Gauge rows: the leading columns become the tags, column {@code valueIndex} the value. */
    private static List<MultiGauge.Row<?>> rows(List<Object[]> data, int valueIndex, String... tagKeys) {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        for (Object[] r : data) {
            String[] kv = new String[tagKeys.length * 2];
            for (int i = 0; i < tagKeys.length; i++) {
                kv[2 * i] = tagKeys[i];
                kv[2 * i + 1] = FlowMetrics.label((String) r[i]);
            }
            rows.add(MultiGauge.Row.of(Tags.of(kv), (Number) r[valueIndex]));
        }
        return rows;
    }
}
