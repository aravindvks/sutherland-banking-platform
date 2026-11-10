package bank.pbp.bff.compliance;

import bank.pbp.bff.BffModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the compliance module as present in this build (GET /v1/modules). */
@Configuration
public class ComplianceModule {

    @Bean
    BffModule complianceBffModule() {
        return new BffModule("compliance");
    }
}
