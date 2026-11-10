package bank.pbp.orchestrator.compliance;

import bank.pbp.common.error.PbpException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff portal API (HLD Control Room): what needs attention, review decisions, and journey
 * search by correlation ID across sagas and payments.
 */
@RestController
@RequestMapping("/v1/staff")
public class StaffController {

    private final JdbcClient jdbc;
    private final WorkflowClient temporal;

    public StaffController(JdbcClient jdbc, WorkflowClient temporal) {
        this.jdbc = jdbc;
        this.temporal = temporal;
    }

    @GetMapping("/queue")
    public Map<String, Object> queue() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviews", jdbc.sql("""
                        SELECT saga_id::text AS "sagaId", customer_id::text AS "customerId",
                               correlation_id AS "correlationId", current_step AS step, started_at::text AS "startedAt"
                        FROM saga WHERE saga_type = 'FREEZE' AND status = 'WAITING'
                        ORDER BY started_at""")
                .query().listOfRows());
        out.put("flaggedPayments", jdbc.sql("""
                        SELECT payment_id::text AS "paymentId", customer_id::text AS "customerId",
                               amount::text AS amount, creditor::text AS creditor, correlation_id AS "correlationId",
                               created_at::text AS "createdAt"
                        FROM payment WHERE status = 'FLAGGED' ORDER BY created_at""")
                .query().listOfRows());
        out.put("heldPayments", jdbc.sql("""
                        SELECT payment_id::text AS "paymentId", customer_id::text AS "customerId",
                               amount::text AS amount, payment_type AS "paymentType", creditor::text AS creditor,
                               correlation_id AS "correlationId", created_at::text AS "createdAt"
                        FROM payment WHERE status = 'HELD' ORDER BY created_at""")
                .query().listOfRows());
        out.put("creditReferrals", jdbc.sql("""
                        SELECT application_id::text AS "applicationId", customer_id::text AS "customerId", name,
                               requested_limit::text AS "requestedLimit", offered_limit::text AS "engineLimit",
                               annual_income::text AS "annualIncome", reasons, correlation_id AS "correlationId",
                               created_at::text AS "createdAt"
                        FROM credit_application WHERE status = 'REFERRED' ORDER BY created_at""")
                .query().listOfRows());
        out.put("creditArrears", jdbc.sql("""
                        SELECT account_id AS "accountId", customer_id::text AS "customerId", card_last4 AS last4,
                               credit_limit::text AS "creditLimit", arrears_count AS "arrearsCount",
                               updated_at::text AS "updatedAt"
                        FROM credit_account WHERE status = 'ARREARS' ORDER BY updated_at""")
                .query().listOfRows());
        out.put("stuckSagas", jdbc.sql("""
                        SELECT saga_id::text AS "sagaId", saga_type AS type, customer_id::text AS "customerId",
                               current_step AS step, status, updated_at::text AS "updatedAt"
                        FROM saga WHERE status IN ('RUNNING','WAITING') AND saga_type <> 'FREEZE'
                          AND NOT (saga_type = 'CREDIT_APPLICATION' AND status = 'WAITING')
                          AND updated_at < now() - interval '10 minutes'
                        ORDER BY updated_at""")
                .query().listOfRows());
        return out;
    }

    @PostMapping("/reviews/{sagaId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> review(@PathVariable UUID sagaId, @Valid @RequestBody Review body) {
        try {
            temporal.newWorkflowStub(ComplianceWorkflow.class, "compliance-" + sagaId)
                    .reviewDecision(body.decision(), body.reviewer(), body.note());
        } catch (WorkflowNotFoundException e) {
            throw new PbpException("GEN-NOTFOUND-001", 404, "Not found", "No open review " + sagaId);
        }
        return Map.of("sagaId", sagaId.toString(), "decision", body.decision(), "status", "SUBMITTED");
    }

    /** Decision on a HELD high-value payment: a signal to its payment saga. */
    @PostMapping("/payments/{paymentId}/decision")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> paymentDecision(@PathVariable UUID paymentId, @Valid @RequestBody PaymentDecision body) {
        String status = jdbc.sql("SELECT status FROM payment WHERE payment_id = :id")
                .param("id", paymentId).query(String.class).optional()
                .orElseThrow(() -> new PbpException("GEN-NOTFOUND-001", 404, "Not found", "No payment " + paymentId));
        if (!"HELD".equals(status)) {
            throw new PbpException("PAY-STATE-001", 409, "Payment not held", "Payment " + paymentId + " is " + status);
        }
        try {
            temporal.newWorkflowStub(bank.pbp.orchestrator.payments.PaymentWorkflow.class, "payment-" + paymentId)
                    .reviewDecision(body.decision(), body.reviewer(), body.note());
        } catch (WorkflowNotFoundException e) {
            throw new PbpException("GEN-NOTFOUND-001", 404, "Not found", "No running payment " + paymentId);
        }
        return Map.of("paymentId", paymentId.toString(), "decision", body.decision(), "status", "SUBMITTED");
    }

    @GetMapping("/journeys/{correlationId}")
    public Map<String, Object> journey(@PathVariable String correlationId) {
        List<Map<String, Object>> sagas = jdbc.sql("""
                        SELECT saga_id::text AS "sagaId", saga_type AS type, customer_id::text AS "customerId",
                               current_step AS step, status, started_at::text AS "startedAt", updated_at::text AS "updatedAt"
                        FROM saga WHERE correlation_id = :id ORDER BY started_at""")
                .param("id", correlationId).query().listOfRows();
        List<Map<String, Object>> payments = jdbc.sql("""
                        SELECT payment_id::text AS "paymentId", payment_type AS type, status, amount::text AS amount,
                               finastra_ref AS "finastraRef", created_at::text AS "createdAt"
                        FROM payment WHERE correlation_id = :id ORDER BY created_at""")
                .param("id", correlationId).query().listOfRows();
        List<Map<String, Object>> history = jdbc.sql("""
                        SELECT payment_id::text AS "paymentId", from_status AS "from", to_status AS "to", reason,
                               actor, at::text AS at
                        FROM payment_status_history WHERE correlation_id = :id ORDER BY at""")
                .param("id", correlationId).query().listOfRows();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("correlationId", correlationId);
        out.put("sagas", sagas);
        out.put("payments", payments);
        out.put("paymentHistory", history);
        return out;
    }

    @GetMapping("/sagas")
    public List<Map<String, Object>> recentSagas() {
        return jdbc.sql("""
                        SELECT saga_id::text AS "sagaId", saga_type AS type, customer_id::text AS "customerId",
                               correlation_id AS "correlationId", current_step AS step, status,
                               updated_at::text AS "updatedAt"
                        FROM saga ORDER BY updated_at DESC LIMIT 50""")
                .query().listOfRows();
    }

    public record PaymentDecision(@NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
                                  @NotBlank String reviewer,
                                  String note) {
    }

    public record Review(@NotBlank @Pattern(regexp = "CLEAR|CONFIRM") String decision,
                         @NotBlank String reviewer,
                         String note) {
    }
}
