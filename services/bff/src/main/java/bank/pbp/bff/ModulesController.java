package bank.pbp.bff;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The modules in this build (phase A module switches). The web app shows only their tabs and panels. */
@RestController
public class ModulesController {

    private final List<String> modules;

    public ModulesController(List<BffModule> modules) {
        this.modules = modules.stream().map(BffModule::name).sorted().toList();
    }

    @GetMapping("/v1/modules")
    public Map<String, List<String>> modules() {
        return Map.of("modules", modules);
    }
}
