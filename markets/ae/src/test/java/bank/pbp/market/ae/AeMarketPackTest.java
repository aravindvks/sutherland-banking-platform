package bank.pbp.market.ae;

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

class AeMarketPackTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AeMarketAutoConfiguration.class));

    @Test
    void dirhamsUaeTimeAndIbans() {
        context.run(c -> {
            MarketPack ae = c.getBean(MarketPack.class);
            assertThat(ae.code()).isEqualTo("ae");
            assertThat(ae.currency().getCurrencyCode()).isEqualTo("AED");
            assertThat(ae.zone().getId()).isEqualTo("Asia/Dubai");
            assertThat(ae.accountIdentifiers().scheme()).isEqualTo("IBAN");
            assertThat(ae.paymentSchemes()).extracting(PaymentScheme::code).containsExactly("INTERNAL", "AANI", "UAEFTS");
            assertThat(ae.directDebits()).isPresent();
        });
    }

    @Test
    void eidAlEtihadAndTheUaeftsCutOff() {
        context.run(c -> {
            MarketPack ae = c.getBean(MarketPack.class);
            assertThat(ae.calendar().isBusinessDay(LocalDate.parse("2026-12-02"))).isFalse();
            // Tuesday 1 December 2026, 17:00 UAE time (13:00 UTC): after the cut-off; 2 and 3 December are Eid Al
            // Etihad, and Friday is a working day (the weekend is Saturday and Sunday), so UAEFTS opens Friday
            // 4 December 08:00 (04:00 UTC).
            PaymentScheme fts = ae.paymentScheme("UAEFTS").orElseThrow();
            Instant evening = Instant.parse("2026-12-01T13:00:00Z");
            assertThat(fts.window().nextOpening(evening, ae.zone(), ae.calendar()))
                    .isEqualTo(Instant.parse("2026-12-04T04:00:00Z"));
        });
    }

    @Test
    void aaniLimitCannotExceedThePlatform() {
        assertThatThrownBy(() -> new AeMarketPack(new BigDecimal("100000.00"), new BigDecimal("50000.00"),
                new BigDecimal("50000.01"), BigDecimal.ZERO, new BigDecimal("5000000.00"), BigDecimal.ZERO,
                LocalTime.of(8, 0), LocalTime.of(16, 0), Set.of(), new BigDecimal("20000.00"), Set.of()))
                .hasMessageContaining("AED 50,000");
    }
}
