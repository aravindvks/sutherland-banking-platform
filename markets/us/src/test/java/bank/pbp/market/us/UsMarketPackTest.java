package bank.pbp.market.us;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.MarketPack;
import bank.pbp.common.market.PaymentScheme;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class UsMarketPackTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(UsMarketAutoConfiguration.class));

    @Test
    void dollarsEasternTimeAndRoutingNumbers() {
        context.run(c -> {
            MarketPack us = c.getBean(MarketPack.class);
            assertThat(us.code()).isEqualTo("us");
            assertThat(us.currency().getCurrencyCode()).isEqualTo("USD");
            assertThat(us.zone().getId()).isEqualTo("America/New_York");
            assertThat(us.locale().toLanguageTag()).isEqualTo("en-US");
            assertThat(us.accountIdentifiers().scheme()).isEqualTo("US_ABA");
            assertThat(us.nameCheck()).isEmpty();
            assertThat(us.directDebits()).isPresent();
        });
    }

    @Test
    void schemesAndWhatTheCustomerCanPick() {
        context.run(c -> {
            MarketPack us = c.getBean(MarketPack.class);
            assertThat(us.paymentSchemes()).extracting(PaymentScheme::code)
                    .containsExactly("INTERNAL", "RTP", "FEDNOW", "ACH", "WIRE");
            assertThat(us.customerChoices()).extracting(PaymentScheme::code).containsExactly("RTP", "ACH", "WIRE");
            assertThat(us.customerChoices().get(0).name()).isEqualTo("Instant payment");
            PaymentScheme wire = us.paymentScheme("WIRE").orElseThrow();
            assertThat(wire.fee()).isEqualByComparingTo("25.00");
            assertThat(wire.window().opens()).isEqualTo(LocalTime.of(9, 0));
            assertThat(wire.window().cutOff()).isEqualTo(LocalTime.of(17, 0));
            assertThat(wire.allows(new BigDecimal("1000000.00"))).isTrue();
        });
    }

    @Test
    void federalReserveHolidays() {
        context.run(c -> {
            MarketPack us = c.getBean(MarketPack.class);
            // Thanksgiving 2026 is a Thursday; Independence Day 2027 is a Sunday, so Monday 5 July closes.
            assertThat(us.calendar().isBusinessDay(LocalDate.parse("2026-11-26"))).isFalse();
            assertThat(us.calendar().isBusinessDay(LocalDate.parse("2027-07-05"))).isFalse();
            // Christmas 2027 is a Saturday: the Reserve Banks stay open on Friday 24 December.
            assertThat(us.calendar().isBusinessDay(LocalDate.parse("2027-12-24"))).isTrue();
        });
    }

    @Test
    void wiresQueueAfterTheCutOffUntilTheNextBusinessDay() {
        context.run(c -> {
            MarketPack us = c.getBean(MarketPack.class);
            PaymentScheme wire = us.paymentScheme("WIRE").orElseThrow();
            // Wednesday 25 November 2026, 18:00 ET (23:00 UTC): closed; Thursday is Thanksgiving, so Friday 09:00 ET.
            Instant evening = Instant.parse("2026-11-25T23:00:00Z");
            assertThat(wire.window().isOpen(evening, us.zone(), us.calendar())).isFalse();
            assertThat(wire.window().nextOpening(evening, us.zone(), us.calendar()))
                    .isEqualTo(Instant.parse("2026-11-27T14:00:00Z"));
        });
    }

    @Test
    void settingsOverrideTheDefaults() {
        context.withPropertyValues("pbp.market.us.wire.fee=30.00", "pbp.market.us.instant-limit=100000.00",
                "pbp.market.us.holidays=").run(c -> {
            MarketPack us = c.getBean(MarketPack.class);
            assertThat(us.paymentScheme("WIRE").orElseThrow().fee()).isEqualByComparingTo("30.00");
            assertThat(us.paymentScheme("RTP").orElseThrow().allows(new BigDecimal("100000.00"))).isTrue();
            assertThat(us.calendar().holidays()).isEmpty();
        });
    }
}
