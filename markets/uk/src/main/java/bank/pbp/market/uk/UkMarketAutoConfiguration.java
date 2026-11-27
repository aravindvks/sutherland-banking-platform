package bank.pbp.market.uk;

import bank.pbp.common.market.MarketPack;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the UK pack in every app built with it. Defaults equal today's in services/orchestrator's
 * application.yml, so apps without those keys (the BFF) see the same values.
 */
@AutoConfiguration
public class UkMarketAutoConfiguration {

    /** England and Wales bank holidays, the same default as CHAPS_HOLIDAYS in services/orchestrator's application.yml. */
    static final String DEFAULT_HOLIDAYS = "2026-12-25,2026-12-28,2027-01-01,2027-03-26,2027-03-29,2027-05-03,"
            + "2027-05-31,2027-08-30,2027-12-27,2027-12-28";

    @Bean
    @ConditionalOnMissingBean(MarketPack.class)
    UkMarketPack ukMarketPack(
            @Value("${pbp.payments.single-limit:25000.00}") BigDecimal singleLimit,
            @Value("${pbp.payments.review-threshold:10000.00}") BigDecimal reviewThreshold,
            @Value("${pbp.payments.chaps.limit:1000000.00}") BigDecimal chapsLimit,
            @Value("${pbp.payments.chaps.fee:20.00}") BigDecimal chapsFee,
            @Value("${pbp.payments.chaps.opens:06:00}") String chapsOpens,
            @Value("${pbp.payments.chaps.cut-off:17:30}") String chapsCutOff,
            @Value("${pbp.payments.chaps.holidays:" + DEFAULT_HOLIDAYS + "}") String holidays,
            @Value("${pbp.credit.daily-limit:5000.00}") BigDecimal dailyCardSpend) {
        return new UkMarketPack(singleLimit, reviewThreshold, chapsLimit, chapsFee,
                LocalTime.parse(chapsOpens), LocalTime.parse(chapsCutOff), UkMarketPack.parseHolidays(holidays),
                dailyCardSpend);
    }
}
