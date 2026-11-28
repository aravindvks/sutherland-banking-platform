// The Jamaica market pack (docs/design/market-packs.md). Upcoming: built and unit-tested, not yet run end to end.
package bank.pbp.market.jm;

import bank.pbp.common.market.MarketPack;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the Jamaica pack in every app built with it. Shared limits use the same keys as the other packs; Jamaica
 * settings use pbp.market.jm.*. Defaults other than the J$1 million threshold are placeholders to confirm with the
 * client bank, not its tariff.
 */
@AutoConfiguration
public class JmMarketAutoConfiguration {

    /**
     * Weekday bank closures from 25 December 2026 to the end of 2027. A holiday on a Sunday is kept on the Monday
     * (Labour Day 24 May, Emancipation Day 2 August, Boxing Day 27 December 2027). Whether a Saturday holiday
     * (Boxing Day 2026, Christmas Day 2027) closes a weekday is to confirm with the client bank.
     */
    static final String DEFAULT_HOLIDAYS = "2026-12-25,2027-01-01,2027-02-10,2027-03-26,2027-03-29,2027-05-24,"
            + "2027-08-02,2027-08-06,2027-10-18,2027-12-27";

    @Bean
    @ConditionalOnMissingBean(MarketPack.class)
    JmMarketPack jmMarketPack(
            @Value("${pbp.payments.single-limit:3000000.00}") BigDecimal singleLimit,
            @Value("${pbp.payments.review-threshold:1500000.00}") BigDecimal reviewThreshold,
            @Value("${pbp.market.jm.ach-threshold:1000000.00}") BigDecimal achThreshold,
            @Value("${pbp.market.jm.rtgs.limit:50000000.00}") BigDecimal rtgsLimit,
            @Value("${pbp.market.jm.rtgs.fee:250.00}") BigDecimal rtgsFee,
            @Value("${pbp.market.jm.rtgs.opens:08:30}") String rtgsOpens,
            @Value("${pbp.market.jm.rtgs.cut-off:13:00}") String rtgsCutOff,
            @Value("${pbp.market.jm.holidays:" + DEFAULT_HOLIDAYS + "}") String holidays,
            @Value("${pbp.credit.daily-limit:750000.00}") BigDecimal dailyCardSpend,
            @Value("${pbp.market.jm.bank-code-pattern:}") String bankPattern,
            @Value("${pbp.market.jm.branch-code-pattern:}") String branchPattern,
            @Value("${pbp.market.jm.account-number-pattern:}") String accountPattern) {
        return new JmMarketPack(singleLimit, reviewThreshold, achThreshold, rtgsLimit, rtgsFee,
                LocalTime.parse(rtgsOpens), LocalTime.parse(rtgsCutOff), JmMarketPack.parseHolidays(holidays),
                dailyCardSpend, new JmAccountScheme(or(bankPattern, BANK_PATTERN), or(branchPattern, BRANCH_PATTERN),
                        or(accountPattern, ACCOUNT_PATTERN)));
    }

    // Provisional formats, kept out of the @Value defaults because their braces would read as placeholder syntax.
    static final String BANK_PATTERN = "\\d{2,4}";
    static final String BRANCH_PATTERN = "\\d{1,5}";
    static final String ACCOUNT_PATTERN = "\\d{4,20}";

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
