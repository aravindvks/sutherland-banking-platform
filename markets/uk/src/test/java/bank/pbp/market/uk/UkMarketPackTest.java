package bank.pbp.market.uk;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.MarketPack;
import bank.pbp.common.market.PaymentScheme;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The UK pack carries exactly today's rules (services/orchestrator application.yml), so moving to it changes nothing. */
class UkMarketPackTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(UkMarketAutoConfiguration.class));

    @Test
    void defaultsAreTodaysUkValues() {
        context.run(ctx -> {
            MarketPack uk = ctx.getBean(MarketPack.class);
            assertThat(uk.code()).isEqualTo("uk");
            assertThat(uk.currency().getCurrencyCode()).isEqualTo("GBP");
            assertThat(uk.zone().getId()).isEqualTo("Europe/London");
            assertThat(uk.locale()).isEqualTo(Locale.UK);

            assertThat(uk.limits().singlePayment()).isEqualByComparingTo("25000.00");
            assertThat(uk.limits().reviewThreshold()).isEqualByComparingTo("10000.00");
            assertThat(uk.limits().dailyCardSpend()).isEqualByComparingTo("5000.00");

            assertThat(uk.paymentSchemes()).extracting(PaymentScheme::code).containsExactly("INTERNAL", "FPS", "CHAPS");
            PaymentScheme chaps = uk.paymentScheme("CHAPS").orElseThrow();
            assertThat(chaps.instant()).isFalse();
            assertThat(chaps.limit()).hasValueSatisfying(l -> assertThat(l).isEqualByComparingTo("1000000.00"));
            assertThat(chaps.fee()).isEqualByComparingTo("20.00");
            assertThat(chaps.window().opens()).isEqualTo(LocalTime.of(6, 0));
            assertThat(chaps.window().cutOff()).isEqualTo(LocalTime.of(17, 30));
            assertThat(uk.paymentScheme("FPS").orElseThrow().window().always()).isTrue();

            assertThat(uk.calendar().weekend()).containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            assertThat(uk.calendar().holidays()).hasSize(10).contains(LocalDate.parse("2026-12-25"));

            assertThat(uk.nameCheck()).hasValueSatisfying(n -> assertThat(n.name()).isEqualTo("Confirmation of Payee"));
            assertThat(uk.directDebits()).hasValueSatisfying(d -> assertThat(d.originatorIdName()).isEqualTo("Service user number"));
        });
    }

    @Test
    void readsTheSameConfigurationKeysAsTheOrchestrator() {
        context.withPropertyValues(
                "pbp.payments.single-limit=30000.00",
                "pbp.payments.review-threshold=5000.00",
                "pbp.payments.chaps.limit=2000000.00",
                "pbp.payments.chaps.fee=25.00",
                "pbp.payments.chaps.opens=07:00",
                "pbp.payments.chaps.cut-off=16:00",
                "pbp.payments.chaps.holidays=2027-01-01, 2027-12-27",
                "pbp.credit.daily-limit=2500.00").run(ctx -> {
            MarketPack uk = ctx.getBean(MarketPack.class);
            assertThat(uk.limits().singlePayment()).isEqualByComparingTo("30000.00");
            assertThat(uk.limits().reviewThreshold()).isEqualByComparingTo("5000.00");
            assertThat(uk.limits().dailyCardSpend()).isEqualByComparingTo("2500.00");
            PaymentScheme chaps = uk.paymentScheme("CHAPS").orElseThrow();
            assertThat(chaps.fee()).isEqualByComparingTo("25.00");
            assertThat(chaps.window().opens()).isEqualTo(LocalTime.of(7, 0));
            assertThat(chaps.window().cutOff()).isEqualTo(LocalTime.of(16, 0));
            assertThat(uk.calendar().holidays()).containsExactlyInAnyOrder(LocalDate.parse("2027-01-01"),
                    LocalDate.parse("2027-12-27"));
        });
    }

    @Test
    void schemeLimitsMatchTodaysChecks() {
        UkMarketPack uk = new UkMarketPack(new BigDecimal("25000.00"), new BigDecimal("10000.00"),
                new BigDecimal("1000000.00"), new BigDecimal("20.00"), LocalTime.of(6, 0), LocalTime.of(17, 30),
                java.util.Set.of(), new BigDecimal("5000.00"));
        assertThat(uk.paymentScheme("FPS").orElseThrow().allows(new BigDecimal("25000.00"))).isTrue();
        assertThat(uk.paymentScheme("FPS").orElseThrow().allows(new BigDecimal("25000.01"))).isFalse();
        assertThat(uk.paymentScheme("CHAPS").orElseThrow().allows(new BigDecimal("1000000.00"))).isTrue();
        assertThat(uk.paymentScheme("BACS")).isEmpty();
    }

    @Test
    void blankHolidayListMeansNone() {
        assertThat(UkMarketPack.parseHolidays("")).isEmpty();
        assertThat(UkMarketPack.parseHolidays(" 2026-12-25 ,,2026-12-28")).hasSize(2);
    }
}
