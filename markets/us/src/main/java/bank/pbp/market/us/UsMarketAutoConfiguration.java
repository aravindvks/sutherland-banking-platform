// The US market pack (docs/design/market-packs.md). Upcoming: built and unit-tested, not yet run end to end.
package bank.pbp.market.us;

import bank.pbp.common.market.MarketPack;
import java.math.BigDecimal;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the US pack in every app built with it. Limits shared with the UK use the same keys
 * (pbp.payments.single-limit, pbp.payments.review-threshold, pbp.credit.daily-limit); US schemes use
 * pbp.market.us.*. Defaults are placeholders to confirm with the client bank, not its tariff.
 */
@AutoConfiguration
public class UsMarketAutoConfiguration {

    /**
     * Days the Federal Reserve Banks close, from the Federal Reserve's published schedule: a holiday on a Sunday
     * closes the Monday; on a Saturday the Reserve Banks stay open on the Friday, so neither day is listed.
     */
    static final String DEFAULT_HOLIDAYS = "2026-11-26,2026-12-25,2027-01-01,2027-01-18,2027-02-15,2027-05-31,"
            + "2027-07-05,2027-09-06,2027-10-11,2027-11-11,2027-11-25";

    @Bean
    @ConditionalOnMissingBean(MarketPack.class)
    UsMarketPack usMarketPack(
            @Value("${pbp.payments.single-limit:25000.00}") BigDecimal singleLimit,
            @Value("${pbp.payments.review-threshold:10000.00}") BigDecimal reviewThreshold,
            @Value("${pbp.market.us.instant-limit:25000.00}") BigDecimal instantLimit,
            @Value("${pbp.market.us.wire.limit:1000000.00}") BigDecimal wireLimit,
            @Value("${pbp.market.us.wire.fee:25.00}") BigDecimal wireFee,
            @Value("${pbp.market.us.wire.opens:09:00}") String wireOpens,
            @Value("${pbp.market.us.wire.cut-off:17:00}") String wireCutOff,
            @Value("${pbp.market.us.holidays:" + DEFAULT_HOLIDAYS + "}") String holidays,
            @Value("${pbp.credit.daily-limit:5000.00}") BigDecimal dailyCardSpend,
            @Value("${pbp.market.us.rtp-unreachable:}") String rtpUnreachable,
            @Value("${pbp.market.us.fednow-unreachable:}") String fedNowUnreachable) {
        return new UsMarketPack(singleLimit, reviewThreshold, instantLimit, wireLimit, wireFee,
                LocalTime.parse(wireOpens), LocalTime.parse(wireCutOff), UsMarketPack.parseHolidays(holidays),
                dailyCardSpend, UsMarketPack.parseList(rtpUnreachable), UsMarketPack.parseList(fedNowUnreachable));
    }
}
