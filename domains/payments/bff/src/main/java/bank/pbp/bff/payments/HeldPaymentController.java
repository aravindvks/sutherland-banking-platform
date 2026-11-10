package bank.pbp.bff.payments;

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

/** Control Room: approve or reject a HELD high-value payment (compliance role, see SecurityConfig). */
@RestController
@RequestMapping("/v1/staff")
public class HeldPaymentController {

    private final RestClient orchestrator;
    private final Caller caller;

    public HeldPaymentController(Caller caller, RestClient.Builder builder, @Value("${pbp.services.orchestrator-url}") String url) {
        this.caller = caller;
        this.orchestrator = builder.clone().baseUrl(url).build();
    }

    @PostMapping("/payments/{paymentId}/decision")
    public JsonNode paymentDecision(@PathVariable UUID paymentId, @Valid @RequestBody PaymentDecision body,
                                    HttpServletRequest request) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("decision", body.decision());
        req.put("reviewer", caller.username(request));
        req.put("note", body.note());
        return VendorErrors.call("orchestrator", "staff.payment_decision", () -> orchestrator.post()
                .uri("/v1/staff/payments/{id}/decision", paymentId).body(req).retrieve().body(JsonNode.class));
    }

    public record PaymentDecision(@NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision, String note) {
    }
}
