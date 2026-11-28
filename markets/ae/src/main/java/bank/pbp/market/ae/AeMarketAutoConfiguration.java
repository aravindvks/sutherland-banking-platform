// The UAE market pack (docs/design/market-packs.md). Upcoming: built and unit-tested, not yet run end to end.
package bank.pbp.market.ae;

import bank.pbp.common.market.MarketPack;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the UAE pack in every app built with it. Shared limits use the same keys as the other packs; UAE settings
 * use pbp.market.ae.*. Defaults are placeholders to confirm with the client bank, not its tariff.
 */
@AutoConfiguration
public class AeMarketAutoConfiguration {

    /**
     * Weekday public holidays to the end of 2027 (Cabinet Resolution No. 27 of 2024). Eid Al Etihad and New Year's
     * Day are fixed. Eid Al Fitr (9 to 11 March 2027, to 12 March if Ramadan lasts 30 days) and Eid Al Adha (Arafah
     * Day 15 May, Eid 16 to 18 May) are expected dates until the moon sighting; weekend days are left out. Hijri New
     * Year and the Prophet's birthday are announced nearer the time and added then.
     */
    static final String DEFAULT_HOLIDAYS = "2026-12-02,2026-12-03,2027-01-01,2027-03-09,2027-03-10,2027-03-11,"
            + "2027-05-17,2027-05-18,2027-12-02,2027-12-03";

    @Bean
    @ConditionalOnMissingBean(MarketPack.class)
    AeMarketPack aeMarketPack(
            @Value("${pbp.payments.single-limit:100000.00}") BigDecimal singleLimit,
            @Value("${pbp.payments.review-threshold:50000.00}") BigDecimal reviewThreshold,
            @Value("${pbp.market.ae.aani.limit:10000.00}") BigDecimal aaniLimit,
            @Value("${pbp.market.ae.aani.fee:0.00}") BigDecimal aaniFee,
            @Value("${pbp.market.ae.uaefts.limit:5000000.00}") BigDecimal ftsLimit,
            @Value("${pbp.market.ae.uaefts.fee:0.00}") BigDecimal ftsFee,
            @Value("${pbp.market.ae.uaefts.opens:08:00}") String ftsOpens,
            @Value("${pbp.market.ae.uaefts.cut-off:16:00}") String ftsCutOff,
            @Value("${pbp.market.ae.holidays:" + DEFAULT_HOLIDAYS + "}") String holidays,
            @Value("${pbp.credit.daily-limit:20000.00}") BigDecimal dailyCardSpend,
            @Value("${pbp.market.ae.aani-unreachable:}") String aaniUnreachable) {
        return new AeMarketPack(singleLimit, reviewThreshold, aaniLimit, aaniFee, ftsLimit, ftsFee,
                LocalTime.parse(ftsOpens), LocalTime.parse(ftsCutOff), AeMarketPack.parseHolidays(holidays),
                dailyCardSpend, AeMarketPack.parseList(aaniUnreachable));
    }
}
