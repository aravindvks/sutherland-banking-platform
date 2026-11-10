package bank.pbp.bff.payments;

import bank.pbp.bff.BffModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the payments module as present in this build (GET /v1/modules). */
@Configuration
public class PaymentsModule {

    @Bean
    BffModule paymentsBffModule() {
        return new BffModule("payments");
    }
}
