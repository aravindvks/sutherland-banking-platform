package bank.pbp.bff.creditcards;

import bank.pbp.bff.security.Caller;
import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.error.PbpException;
import bank.pbp.common.error.VendorErrors;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Credit cards (Thredd credit processing). The customer applies (a soft search: no mark on their
 * credit file), reads the offer and accepts the agreement, then sees the balance, available credit
 * and statements, repays from their current account, and can withdraw within 14 days.
 * Commands go to card-commands (creditcard.apply, .accept, .repay, .withdraw).
 */
@RestController
@RequestMapping("/v1/credit-cards")
public class CreditCardController {

    static final String MONEY = "\\d{1,9}(\\.\\d{2})?";

    private final EventPublisher events;
    private final RestClient orchestrator;
    private final Caller caller;

    public CreditCardController(EventPublisher events, Caller caller, RestClient.Builder builder,
                                @Value("${pbp.services.orchestrator-url}") String url) {
        this.events = events;
        this.caller = caller;
        this.orchestrator = builder.clone().baseUrl(url).build();
    }

    @GetMapping
    public JsonNode mine(HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        return VendorErrors.call("orchestrator", "creditcards.list", () -> orchestrator.get()
                .uri("/v1/customers/{id}/credit-cards", customerId).retrieve().body(JsonNode.class));
    }

    @PostMapping("/applications")
    public ResponseEntity<Map<String, Object>> apply(@Valid @RequestBody NewApplication body, HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", Ids.uuidV7().toString());
        data.put("name", body.name());
        data.put("annualIncome", body.annualIncome());
        data.put("employmentStatus", body.employmentStatus());
        data.put("monthlyHousing", body.monthlyHousing() == null ? "0" : body.monthlyHousing());
        data.put("monthlyCommitments", body.monthlyCommitments() == null ? "0" : body.monthlyCommitments());
        data.put("requestedLimit", body.requestedLimit());
        return command("creditcard.apply", data, request);
    }

    /** The customer has read the pre-contract information and accepts the credit agreement. */
    @PostMapping("/applications/{applicationId}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable UUID applicationId,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", applicationId.toString());
        data.put("agreementVersion", body == null ? "v1" : String.valueOf(body.getOrDefault("agreementVersion", "v1")));
        return command("creditcard.accept", data, request);
    }

    @PostMapping("/{accountId}/repayments")
    public ResponseEntity<Map<String, Object>> repay(@PathVariable String accountId, @Valid @RequestBody NewRepayment body,
                                                     HttpServletRequest request) {
        if (new BigDecimal(body.amount()).signum() <= 0) {
            throw new PbpException("GEN-VALID-001", 400, "Request is not valid", "Amount must be more than zero");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("repaymentId", Ids.uuidV7().toString());
        data.put("accountId", accountId);
        data.put("debitAccount", body.debitAccount());
        data.put("amount", new BigDecimal(body.amount()).setScale(2).toPlainString());
        return command("creditcard.repay", data, request);
    }

    /** Right to withdraw within 14 days of the agreement (Consumer Credit Act s66A), with nothing owed. */
    @PostMapping("/{accountId}/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(@PathVariable String accountId, HttpServletRequest request) {
        return command("creditcard.withdraw", Map.of("accountId", accountId), request);
    }

    private ResponseEntity<Map<String, Object>> command(String type, Map<String, Object> data, HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        String correlationId = Ids.newCorrelationId(JourneyType.CRD);
        Correlation.set(correlationId);
        events.publish(Topics.CARD_COMMANDS, EventEnvelope.of(type, "bff", correlationId, null, customerId.toString(), data));
        Map<String, Object> out = new LinkedHashMap<>(data);
        out.put("status", "ACCEPTED");
        out.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(Correlation.HEADER, correlationId).body(out);
    }

    public record NewApplication(@NotBlank String name,
                                 @NotBlank @Pattern(regexp = MONEY) String annualIncome,
                                 @NotBlank @Pattern(regexp = "EMPLOYED|SELF_EMPLOYED|RETIRED|STUDENT|UNEMPLOYED")
                                 String employmentStatus,
                                 @Pattern(regexp = MONEY) String monthlyHousing,
                                 @Pattern(regexp = MONEY) String monthlyCommitments,
                                 @NotBlank @Pattern(regexp = MONEY) String requestedLimit,
                                 boolean consentToSoftSearch) {
        @AssertTrue(message = "the customer must agree to a soft credit search")
        public boolean isConsentGiven() {
            return consentToSoftSearch;
        }
    }

    public record NewRepayment(@NotBlank String debitAccount, @NotBlank @Pattern(regexp = MONEY) String amount) {
    }
}
