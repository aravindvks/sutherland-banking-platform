package bank.pbp.bff.staff;

import bank.pbp.bff.security.Caller;
import bank.pbp.common.error.VendorErrors;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/**
 * Staff portal entry point for what every bank has: the work queue, recent sagas, a journey by correlation ID and
 * linking logins. Each domain module adds its own Control Room routes under /v1/staff (reviews, held payments,
 * underwriting, reconciliation). Viewing needs the staff role; decisions and reconciliation runs need compliance
 * (see SecurityConfig). The signed-in reviewer is recorded as the actor on every change.
 */
@RestController
@RequestMapping("/v1/staff")
public class StaffController {

    private final RestClient orchestrator;
    private final Caller caller;

    public StaffController(Caller caller, RestClient.Builder builder, @Value("${pbp.services.orchestrator-url}") String url) {
        this.caller = caller;
        this.orchestrator = builder.clone().baseUrl(url).build();
    }

    @GetMapping("/queue")
    public JsonNode queue() {
        return get("/v1/staff/queue");
    }

    @GetMapping("/sagas")
    public JsonNode sagas() {
        return get("/v1/staff/sagas");
    }

    @GetMapping("/journeys/{correlationId}")
    public JsonNode journey(@PathVariable String correlationId) {
        return get("/v1/staff/journeys/" + correlationId);
    }

    /** Links an online banking login to an existing bank customer (e.g. a customer opened in branch). */
    @PostMapping("/logins")
    public Map<String, Object> linkLogin(@Valid @RequestBody LoginLink body, HttpServletRequest request) {
        caller.link(body.username(), body.customerId(), "user:" + caller.username(request));
        return Map.of("username", body.username(), "customerId", body.customerId().toString(), "status", "LINKED");
    }

    public record LoginLink(@NotBlank String username, @NotNull UUID customerId) {
    }

    private JsonNode get(String path) {
        return VendorErrors.call("orchestrator", "staff.get", () -> orchestrator.get().uri(path)
                .retrieve().body(JsonNode.class));
    }
}
