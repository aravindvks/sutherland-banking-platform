package bank.pbp.market.co;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.pbp.common.market.MarketPack;
import bank.pbp.common.market.PaymentScheme;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CoMarketPackTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoMarketAutoConfiguration.class));

    @Test
    void pesosColombiaTimeAndSpanish() {
        context.run(c -> {
            MarketPack co = c.getBean(MarketPack.class);
            assertThat(co.code()).isEqualTo("co");
            assertThat(co.currency().getCurrencyCode()).isEqualTo("COP");
            assertThat(co.zone().getId()).isEqualTo("America/Bogota");
            assertThat(co.locale().toLanguageTag()).isEqualTo("es-CO");
            assertThat(co.paymentSchemes()).extracting(PaymentScheme::code)
                    .containsExactly("INTERNAL", "BREB", "ACH", "CUD");
            assertThat(co.accountIdentifiers().problems(CoAccountScheme.of("1007", "123456789", "SAVINGS"))).isEmpty();
            assertThat(co.accountIdentifiers().problems(CoAccountScheme.of("1007", "123456789", "AHORROS")))
                    .containsOnlyKeys("accountType");
        });
    }

    @Test
    void emilianiMondaysAndTheCudCutOff() {
        context.run(c -> {
            MarketPack co = c.getBean(MarketPack.class);
            assertThat(co.calendar().isBusinessDay(LocalDate.parse("2027-01-11"))).isFalse();   // Reyes Magos, moved
            assertThat(co.calendar().isBusinessDay(LocalDate.parse("2027-01-06"))).isTrue();
            // Wednesday 24 March 2027, 16:00 Colombia time (21:00 UTC): after the cut-off; Thursday and Friday are
            // Holy Week holidays, so CUD opens Monday 29 March 08:00 (13:00 UTC).
            PaymentScheme cud = co.paymentScheme("CUD").orElseThrow();
            assertThat(cud.window().nextOpening(Instant.parse("2027-03-24T21:00:00Z"), co.zone(), co.calendar()))
                    .isEqualTo(Instant.parse("2027-03-29T13:00:00Z"));
        });
    }

    @Test
    void breBLimitCannotExceedBreBsOwnMaximum() {
        assertThatThrownBy(() -> new CoMarketPack(new BigDecimal("50000000.00"), new BigDecimal("20000000.00"),
                new BigDecimal("12110000.01"), new BigDecimal("12110000.00"), new BigDecimal("2000000000.00"),
                BigDecimal.ZERO, LocalTime.of(8, 0), LocalTime.of(15, 0), Set.of(), new BigDecimal("5000000.00"),
                Set.of(), new CoAccountScheme("\\d{3,4}", "\\d{6,20}")))
                .hasMessageContaining("Bre-B's own maximum");
    }
}
