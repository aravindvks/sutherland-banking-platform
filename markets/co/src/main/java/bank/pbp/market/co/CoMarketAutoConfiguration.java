// The Colombia market pack (docs/design/market-packs.md). Upcoming: built and unit-tested, not yet run end to end.
package bank.pbp.market.co;

import bank.pbp.common.market.MarketPack;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the Colombia pack in every app built with it. Shared limits use the same keys as the other packs;
 * Colombia settings use pbp.market.co.*. Defaults other than Bre-B's own maximum are placeholders to confirm with the
 * client bank, not its tariff.
 */
@AutoConfiguration
public class CoMarketAutoConfiguration {

    /**
     * Weekday national holidays from 8 December 2026 to the end of 2027. Holidays moved to the following Monday under
     * Law 51 of 1983 (Ley Emiliani) are on the Monday; Easter 2027 is 28 March. 1 May, 7 August and 25 December
     * 2027 fall on Saturdays and are left out. A new holiday for the Virgen de Chiquinquirá is reported for 2027,
     * but sources differ on the day (9 or 12 July); it is left out until confirmed.
     */
    static final String DEFAULT_HOLIDAYS = "2026-12-08,2026-12-25,2027-01-01,2027-01-11,2027-03-22,2027-03-25,"
            + "2027-03-26,2027-05-10,2027-05-31,2027-06-07,2027-07-05,2027-07-20,2027-08-16,2027-10-18,"
            + "2027-11-01,2027-11-15,2027-12-08";

    // Provisional formats, kept out of the @Value defaults because their braces would read as placeholder syntax.
    static final String BANK_PATTERN = "\\d{3,4}";
    static final String ACCOUNT_PATTERN = "\\d{6,20}";

    @Bean
    @ConditionalOnMissingBean(MarketPack.class)
    CoMarketPack coMarketPack(
            @Value("${pbp.payments.single-limit:50000000.00}") BigDecimal singleLimit,
            @Value("${pbp.payments.review-threshold:20000000.00}") BigDecimal reviewThreshold,
            @Value("${pbp.market.co.breb.limit:5000000.00}") BigDecimal breBLimit,
            @Value("${pbp.market.co.breb.system-limit:12110000.00}") BigDecimal breBSystemLimit,
            @Value("${pbp.market.co.cud.limit:2000000000.00}") BigDecimal cudLimit,
            @Value("${pbp.market.co.cud.fee:0.00}") BigDecimal cudFee,
            @Value("${pbp.market.co.cud.opens:08:00}") String cudOpens,
            @Value("${pbp.market.co.cud.cut-off:15:00}") String cudCutOff,
            @Value("${pbp.market.co.holidays:" + DEFAULT_HOLIDAYS + "}") String holidays,
            @Value("${pbp.credit.daily-limit:5000000.00}") BigDecimal dailyCardSpend,
            @Value("${pbp.market.co.breb-unreachable:}") String breBUnreachable,
            @Value("${pbp.market.co.bank-code-pattern:}") String bankPattern,
            @Value("${pbp.market.co.account-number-pattern:}") String accountPattern) {
        return new CoMarketPack(singleLimit, reviewThreshold, breBLimit, breBSystemLimit, cudLimit, cudFee,
                LocalTime.parse(cudOpens), LocalTime.parse(cudCutOff), CoMarketPack.parseHolidays(holidays),
                dailyCardSpend, CoMarketPack.parseList(breBUnreachable),
                new CoAccountScheme(or(bankPattern, BANK_PATTERN), or(accountPattern, ACCOUNT_PATTERN)));
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
