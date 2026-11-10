package bank.pbp.orchestrator.payments;

import bank.pbp.common.error.PbpException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Orchestrator status API (LLD section 3): a payment with its status history, and a customer's payments. */
@RestController
public class PaymentQueryController {

    private static final String COLUMNS = """
            payment_id::text AS "paymentId", correlation_id AS "correlationId", customer_id::text AS "customerId",
            debit_account AS "debitAccount", creditor::text AS creditor, amount::text AS amount, currency,
            payment_type AS "paymentType", status, finastra_ref AS "finastraRef", created_at::text AS "createdAt",
            cop_result AS "copResult", fee::text AS fee, schedule_id::text AS "scheduleId\"""";

    private final JdbcClient jdbc;

    public PaymentQueryController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/payments/{paymentId}")
    public Map<String, Object> payment(@PathVariable UUID paymentId) {
        Map<String, Object> payment = jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE payment_id = :id")
                .param("id", paymentId)
                .query()
                .listOfRows()
                .stream().findFirst()
                .orElseThrow(() -> new PbpException("GEN-NOTFOUND-001", 404, "Not found", "No payment " + paymentId));
        List<Map<String, Object>> history = jdbc.sql("""
                        SELECT from_status AS "from", to_status AS "to", reason, actor, at::text AS at
                        FROM payment_status_history WHERE payment_id = :id ORDER BY at""")
                .param("id", paymentId)
                .query()
                .listOfRows();
        Map<String, Object> out = new LinkedHashMap<>(payment);
        out.put("history", history);
        return out;
    }

    @GetMapping("/v1/customers/{customerId}/payments")
    public List<Map<String, Object>> customerPayments(@PathVariable UUID customerId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE customer_id = :id ORDER BY created_at DESC LIMIT 50")
                .param("id", customerId)
                .query()
                .listOfRows();
    }
}
