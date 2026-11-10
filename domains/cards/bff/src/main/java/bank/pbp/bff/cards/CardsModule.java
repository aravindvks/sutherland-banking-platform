package bank.pbp.bff.cards;

import bank.pbp.bff.BffModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the cards module as present in this build (GET /v1/modules). */
@Configuration
public class CardsModule {

    @Bean
    BffModule cardsBffModule() {
        return new BffModule("cards");
    }
}
