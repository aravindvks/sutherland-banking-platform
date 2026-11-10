package bank.pbp.orchestrator.directdebits;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Orchestrator status API: a customer's Direct Debit mandates, with their latest collection. */
@RestController
public class DirectDebitQueryController {

    private final JdbcClient jdbc;

    public DirectDebitQueryController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/customers/{customerId}/mandates")
    public List<Map<String, Object>> mandates(@PathVariable UUID customerId) {
        return jdbc.sql("""
                        SELECT m.mandate_id::text AS "mandateId", m.mandate_ref AS "mandateRef",
                               m.originator_name AS "originatorName", m.service_user_number AS "serviceUserNumber",
                               m.payer_reference AS "payerReference", m.debit_account AS "debitAccount", m.status,
                               m.created_at::text AS "createdAt", m.cancelled_at::text AS "cancelledAt",
                               last.amount::text AS "lastAmount", last.status AS "lastStatus",
                               last.created_at::text AS "lastCollectedAt"
                        FROM dd_mandate m
                        LEFT JOIN LATERAL (
                            SELECT p.amount, p.status, p.created_at FROM payment p
                            WHERE p.payment_type = 'DIRECT_DEBIT' AND p.customer_id = m.customer_id
                              AND p.creditor->>'mandateRef' = m.mandate_ref
                            ORDER BY p.created_at DESC LIMIT 1) last ON true
                        WHERE m.customer_id = :id
                        ORDER BY (m.status = 'ACTIVE') DESC, m.created_at DESC""")
                .param("id", customerId)
                .query()
                .listOfRows();
    }
}
