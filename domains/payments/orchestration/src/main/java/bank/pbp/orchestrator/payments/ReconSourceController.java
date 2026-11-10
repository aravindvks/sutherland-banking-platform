package bank.pbp.orchestrator.payments;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconciliation source (LLD section 8): payments the bank believes moved money on a business date.
 * Includes Direct Debits paid (COMPLETED); returned ones never moved money and refunded ones net
 * to zero in Essence. The fee travels with its payment (same Essence reference).
 */
@RestController
public class ReconSourceController {

    private final JdbcClient jdbc;

    public ReconSourceController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/recon/payments")
    public List<Map<String, Object>> payments(@RequestParam LocalDate date) {
        return jdbc.sql("""
                        SELECT payment_id::text AS "paymentId", payment_type AS type, status, amount::text AS amount,
                               COALESCE(fee, 0)::text AS fee, finastra_ref AS "finastraRef",
                               correlation_id AS "correlationId"
                        FROM payment
                        WHERE (created_at AT TIME ZONE 'UTC')::date = :date
                          AND status IN ('COMPLETED','FLAGGED')""")
                .param("date", date)
                .query()
                .listOfRows();
    }
}
