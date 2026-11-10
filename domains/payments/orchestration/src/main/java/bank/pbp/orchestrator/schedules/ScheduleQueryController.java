package bank.pbp.orchestrator.schedules;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Orchestrator status API: a customer's standing orders and scheduled payments. */
@RestController
public class ScheduleQueryController {

    private final JdbcClient jdbc;

    public ScheduleQueryController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/customers/{customerId}/schedules")
    public List<Map<String, Object>> schedules(@PathVariable UUID customerId) {
        return jdbc.sql("""
                        SELECT schedule_id::text AS "scheduleId", kind, frequency, debit_account AS "debitAccount",
                               creditor::text AS creditor, amount::text AS amount, currency, reference, scheme,
                               start_date::text AS "startDate", end_date::text AS "endDate",
                               next_date::text AS "nextDate", payments_made AS "paymentsMade",
                               last_payment_id::text AS "lastPaymentId", last_status AS "lastStatus", status,
                               correlation_id AS "correlationId", created_at::text AS "createdAt"
                        FROM payment_schedule WHERE customer_id = :id
                        ORDER BY (status = 'ACTIVE') DESC, created_at DESC LIMIT 50""")
                .param("id", customerId)
                .query()
                .listOfRows();
    }
}
