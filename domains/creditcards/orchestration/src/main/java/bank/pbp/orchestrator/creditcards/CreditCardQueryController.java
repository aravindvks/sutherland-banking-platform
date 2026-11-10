package bank.pbp.orchestrator.creditcards;

import bank.pbp.common.error.PbpException;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.Underwriting;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Read side for the app and the staff portal: a customer's credit card applications and accounts
 * (with Thredd's live balance and available credit), statements and repayments; and the underwriting
 * queue with its decision endpoint.
 */
@RestController
public class CreditCardQueryController {

    private final JdbcClient jdbc;
    private final RestClient thredd;
    private final CreditCardCommands commands;

    public CreditCardQueryController(JdbcClient jdbc, @Qualifier("threddClient") RestClient thredd,
                                     CreditCardCommands commands) {
        this.jdbc = jdbc;
        this.thredd = thredd;
        this.commands = commands;
    }

    @GetMapping("/v1/customers/{customerId}/credit-cards")
    public Map<String, Object> forCustomer(@PathVariable UUID customerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applications", jdbc.sql("""
                        SELECT application_id::text AS "applicationId", status, requested_limit::text AS "requestedLimit",
                               offered_limit::text AS "offeredLimit", purchase_apr::text AS "purchaseApr", outcome, reasons,
                               account_id AS "accountId", correlation_id AS "correlationId", created_at::text AS "createdAt"
                        FROM credit_application WHERE customer_id = :c ORDER BY created_at DESC LIMIT 5""")
                .param("c", customerId).query().listOfRows());
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (Map<String, Object> a : jdbc.sql("""
                        SELECT account_id AS "accountId", card_token AS "cardToken", card_last4 AS last4,
                               credit_limit::text AS "creditLimit", purchase_apr::text AS "purchaseApr", status,
                               arrears_count AS "arrearsCount", cooling_off_until::text AS "withdrawUntil",
                               opened_at::text AS "openedAt"
                        FROM credit_account WHERE customer_id = :c ORDER BY opened_at DESC""")
                .param("c", customerId).query().listOfRows()) {
            Map<String, Object> view = new LinkedHashMap<>(a);
            String id = String.valueOf(a.get("accountId"));
            try {
                JsonNode live = thredd.get().uri("/v1/credit/accounts/{id}", id).retrieve().body(JsonNode.class);
                view.put("balance", live.path("balance").asText(null));
                view.put("pending", live.path("pending").asText(null));
                view.put("availableCredit", live.path("availableCredit").asText(null));
                view.put("transactions", live.path("transactions"));
            } catch (RestClientException e) {
                view.put("liveError", "Thredd unavailable");
            }
            view.put("statements", statements(id));
            view.put("repayments", jdbc.sql("""
                            SELECT repayment_id::text AS "repaymentId", amount::text AS amount, status, reason,
                                   created_at::text AS "createdAt"
                            FROM credit_repayment WHERE account_id = :id ORDER BY created_at DESC LIMIT 10""")
                    .param("id", id).query().listOfRows());
            accounts.add(view);
        }
        out.put("accounts", accounts);
        return out;
    }

    @GetMapping("/v1/credit-cards/{accountId}/statements")
    public List<Map<String, Object>> statements(@PathVariable String accountId) {
        return jdbc.sql("""
                        SELECT statement_id AS "statementId", statement_date::text AS "statementDate",
                               balance::text AS balance, minimum_payment::text AS "minimumPayment",
                               interest::text AS interest, due_date::text AS "dueDate", status,
                               late_fee::text AS "lateFee"
                        FROM credit_statement WHERE account_id = :id ORDER BY created_at DESC LIMIT 12""")
                .param("id", accountId).query().listOfRows();
    }

    /** The underwriting queue: applications the decision engine referred. */
    @GetMapping("/v1/staff/credit-applications")
    public List<Map<String, Object>> referrals(@RequestParam(defaultValue = "REFERRED") String status) {
        return jdbc.sql("""
                        SELECT application_id::text AS "applicationId", customer_id::text AS "customerId", name,
                               annual_income::text AS "annualIncome", employment_status AS "employmentStatus",
                               monthly_housing::text AS "monthlyHousing", monthly_commitments::text AS "monthlyCommitments",
                               requested_limit::text AS "requestedLimit", offered_limit::text AS "engineLimit",
                               purchase_apr::text AS "purchaseApr", reasons, correlation_id AS "correlationId",
                               created_at::text AS "createdAt"
                        FROM credit_application WHERE status = :s ORDER BY created_at LIMIT 100""")
                .param("s", status).query().listOfRows();
    }

    @PostMapping("/v1/staff/credit-applications/{applicationId}/decision")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> decide(@PathVariable UUID applicationId, @Valid @RequestBody Decision body) {
        String status = jdbc.sql("SELECT status FROM credit_application WHERE application_id = :id")
                .param("id", applicationId).query(String.class).optional()
                .orElseThrow(() -> new PbpException("GEN-NOTFOUND-001", 404, "Not found", "No application " + applicationId));
        if (!"REFERRED".equals(status)) {
            throw new PbpException("CARD-CREDIT-005", 409, "Application not referred",
                    "Application " + applicationId + " is " + status);
        }
        if (!commands.signal(applicationId.toString(),
                wf -> wf.underwriting(new Underwriting(body.decision(), body.creditLimit(), body.reviewer(), body.note())))) {
            throw new PbpException("GEN-NOTFOUND-001", 404, "Not found", "No running application " + applicationId);
        }
        return Map.of("applicationId", applicationId.toString(), "decision", body.decision(), "status", "SUBMITTED");
    }

    public record Decision(@NotBlank @Pattern(regexp = "APPROVE|DECLINE") String decision,
                           @Pattern(regexp = "\\d{1,6}(\\.\\d{2})?") String creditLimit,
                           @NotBlank String reviewer, String note) {
    }
}
