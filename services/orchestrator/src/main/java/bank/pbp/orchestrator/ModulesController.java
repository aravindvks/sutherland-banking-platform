package bank.pbp.orchestrator;

import bank.pbp.orchestrator.temporal.WorkerRegistration;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The domains whose workflows this orchestrator runs (phase A module switches). Internal, like every /v1 route here. */
@RestController
public class ModulesController {

    private final List<String> modules;

    public ModulesController(List<WorkerRegistration> registrations) {
        this.modules = registrations.stream().map(WorkerRegistration::domain).distinct().sorted().toList();
    }

    @GetMapping("/v1/modules")
    public Map<String, List<String>> modules() {
        return Map.of("modules", modules);
    }
}
