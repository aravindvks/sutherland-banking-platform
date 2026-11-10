package bank.pbp.bff.compliance;

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

/** Control Room: a compliance officer's decision on a sanctions review (compliance role, see SecurityConfig). */
@RestController
@RequestMapping("/v1/staff")
public class ReviewController {

    private final RestClient orchestrator;
    private final Caller caller;

    public ReviewController(Caller caller, RestClient.Builder builder, @Value("${pbp.services.orchestrator-url}") String url) {
        this.caller = caller;
        this.orchestrator = builder.clone().baseUrl(url).build();
    }

    @PostMapping("/reviews/{sagaId}")
    public JsonNode review(@PathVariable UUID sagaId, @Valid @RequestBody Decision body, HttpServletRequest request) {
        String staff = caller.username(request);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("decision", body.decision());
        req.put("reviewer", staff);
        req.put("note", body.note());
        return VendorErrors.call("orchestrator", "staff.review", () -> orchestrator.post()
                .uri("/v1/staff/reviews/{id}", sagaId).body(req).retrieve().body(JsonNode.class));
    }

    public record Decision(@NotBlank @Pattern(regexp = "CLEAR|CONFIRM") String decision, String note) {
    }
}
