package bank.pbp.bff.creditcards;

import bank.pbp.bff.BffModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the creditcards module as present in this build (GET /v1/modules). */
@Configuration
public class CreditCardsModule {

    @Bean
    BffModule creditCardsBffModule() {
        return new BffModule("creditcards");
    }
}
