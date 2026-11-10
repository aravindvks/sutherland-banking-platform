package bank.pbp.bff.creditcards;

import bank.pbp.bff.security.Caller;
import bank.pbp.common.error.VendorErrors;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Control Room: the underwriting queue of credit card applications the decision engine referred. */
@RestController
@RequestMapping("/v1/staff")
public class UnderwritingController {

    private final RestClient orchestrator;
    private final Caller caller;

    public UnderwritingController(Caller caller, RestClient.Builder builder, @Value("${pbp.services.orchestrator-url}") String url) {
        this.caller = caller;
        this.orchestrator = builder.clone().baseUrl(url).build();
    }

    @GetMapping("/credit-applications")
    public JsonNode creditReferrals() {
        return VendorErrors.call("orchestrator", "staff.credit_referrals", () -> orchestrator.get()
                .uri("/v1/staff/credit-applications").retrieve().body(JsonNode.class));
    }

    /** An underwriter approves (optionally with a different limit) or declines a referred application. */
    @PostMapping("/credit-applications/{applicationId}/decision")
    public JsonNode creditDecision(@PathVariable UUID applicationId, @Valid @RequestBody CreditDecision body,
                                   HttpServletRequest request) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("decision", body.decision());
        req.put("creditLimit", body.creditLimit());
        req.put("reviewer", caller.username(request));
        req.put("note", body.note());
        return VendorErrors.call("orchestrator", "staff.credit_decision", () -> orchestrator.post()
                .uri("/v1/staff/credit-applications/{id}/decision", applicationId).body(req).retrieve().body(JsonNode.class));
    }

    public record CreditDecision(@NotBlank @Pattern(regexp = "APPROVE|DECLINE") String decision,
                                 @Pattern(regexp = "\\d{1,6}(\\.\\d{2})?") String creditLimit,
                                 String note) {
    }
}
