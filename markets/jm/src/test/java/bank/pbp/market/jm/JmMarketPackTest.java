package bank.pbp.market.jm;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.MarketPack;
import bank.pbp.common.market.PaymentScheme;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JmMarketPackTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JmMarketAutoConfiguration.class));

    @Test
    void jamaicanDollarsAndJamaicaTime() {
        context.run(c -> {
            MarketPack jm = c.getBean(MarketPack.class);
            assertThat(jm.code()).isEqualTo("jm");
            assertThat(jm.currency().getCurrencyCode()).isEqualTo("JMD");
            assertThat(jm.zone().getId()).isEqualTo("America/Jamaica");
            assertThat(jm.locale().toLanguageTag()).isEqualTo("en-JM");
            assertThat(jm.nameCheck()).isEmpty();
            assertThat(jm.directDebits()).isEmpty();
            assertThat(jm.paymentSchemes()).extracting(PaymentScheme::code).containsExactly("INTERNAL", "ACH", "RTGS");
            assertThat(jm.customerChoices()).extracting(PaymentScheme::code).containsExactly("ACH", "RTGS");
        });
    }

    @Test
    void accountFieldsUseTheProvisionalFormatsUnlessConfigured() {
        context.run(c -> {
            MarketPack jm = c.getBean(MarketPack.class);
            assertThat(jm.accountIdentifiers().problems(JmAccountScheme.of("12", "10101", "123456789"))).isEmpty();
            assertThat(jm.accountIdentifiers().problems(JmAccountScheme.of("x", "10101", "12")))
                    .containsOnlyKeys("bankCode", "accountNumber");
        });
        context.withPropertyValues("pbp.market.jm.account-number-pattern=\\d{9}").run(c -> assertThat(
                c.getBean(MarketPack.class).accountIdentifiers().problems(JmAccountScheme.of("12", "1", "1234")))
                .containsOnlyKeys("accountNumber"));
    }

    @Test
    void holidaysAndTheRtgsCutOff() {
        context.run(c -> {
            MarketPack jm = c.getBean(MarketPack.class);
            assertThat(jm.calendar().isBusinessDay(LocalDate.parse("2027-02-10"))).isFalse();   // Ash Wednesday
            assertThat(jm.calendar().isBusinessDay(LocalDate.parse("2027-08-02"))).isFalse();   // Emancipation Day kept
            PaymentScheme rtgs = jm.paymentScheme("RTGS").orElseThrow();
            // Tuesday 9 February 2027, 14:00 Jamaica time (19:00 UTC): after the cut-off; Wednesday is Ash Wednesday,
            // so RTGS opens Thursday 08:30 (13:30 UTC). Jamaica keeps UTC-5 all year.
            Instant afternoon = Instant.parse("2027-02-09T19:00:00Z");
            assertThat(rtgs.window().isOpen(afternoon, jm.zone(), jm.calendar())).isFalse();
            assertThat(rtgs.window().nextOpening(afternoon, jm.zone(), jm.calendar()))
                    .isEqualTo(Instant.parse("2027-02-11T13:30:00Z"));
        });
    }
}
