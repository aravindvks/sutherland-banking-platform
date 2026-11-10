package bank.pbp.bff.recon;

import bank.pbp.common.error.VendorErrors;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Control Room: reconciliation runs and exceptions (running one needs the compliance role, see SecurityConfig). */
@RestController
@RequestMapping("/v1/staff")
public class ReconController {

    private final RestClient recon;

    public ReconController(RestClient.Builder builder, @Value("${pbp.services.recon-url}") String reconUrl) {
        this.recon = builder.clone().baseUrl(reconUrl).build();
    }

    @GetMapping("/recon/runs")
    public JsonNode reconRuns() {
        return VendorErrors.call("recon", "runs.list", () -> recon.get().uri("/v1/recon/runs").retrieve().body(JsonNode.class));
    }

    @GetMapping("/recon/exceptions")
    public JsonNode reconExceptions() {
        return VendorErrors.call("recon", "exceptions.list", () -> recon.get().uri("/v1/recon/exceptions")
                .retrieve().body(JsonNode.class));
    }

    @PostMapping("/recon/runs")
    public JsonNode reconRun() {
        return VendorErrors.call("recon", "runs.trigger", () -> recon.post().uri("/v1/recon/runs")
                .retrieve().body(JsonNode.class));
    }
}
