package bank.pbp.bff.onboarding;

import bank.pbp.bff.BffModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the onboarding module as present in this build (GET /v1/modules). */
@Configuration
public class OnboardingModule {

    @Bean
    BffModule onboardingBffModule() {
        return new BffModule("onboarding");
    }
}
