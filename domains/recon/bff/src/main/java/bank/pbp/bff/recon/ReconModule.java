package bank.pbp.bff.recon;

import bank.pbp.bff.BffModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the recon module as present in this build (GET /v1/modules). */
@Configuration
public class ReconModule {

    @Bean
    BffModule reconBffModule() {
        return new BffModule("recon");
    }
}
