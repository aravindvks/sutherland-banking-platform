package bank.pbp.market.jm;

import bank.pbp.common.market.AccountIdentifierScheme;
import bank.pbp.common.market.BusinessCalendar;
import bank.pbp.common.market.DirectDebitScheme;
import bank.pbp.common.market.Limits;
import bank.pbp.common.market.MarketPack;
import bank.pbp.common.market.NameCheck;
import bank.pbp.common.market.OperatingWindow;
import bank.pbp.common.market.PaymentRouter;
import bank.pbp.common.market.PaymentScheme;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Jamaica market. Upcoming: built and unit-tested, not yet run end to end on the simulators.
 *
 * <p>As published (November 2026): JamClear-RTGS, run by the Bank of Jamaica, settles large-value and time-critical
 * payments; ACH, owned by Automated Payments Limited and operated by JETS Limited, takes payments below the
 * J$1 million value threshold. Banks set their own RTGS customer cut-offs and fees. Those, the limits, the account
 * field formats and the holiday list are configuration (JmMarketAutoConfiguration), confirmed with the client bank
 * before the pack is called ready.
 */
public final class JmMarketPack implements MarketPack {

    public static final String INTERNAL = "INTERNAL";
    public static final String ACH = "ACH";
    public static final String RTGS = "RTGS";

    public static final ZoneId JAMAICA = ZoneId.of("America/Jamaica");
    public static final Currency JMD = Currency.getInstance("JMD");

    private final BusinessCalendar calendar;
    private final List<PaymentScheme> schemes;
    private final Limits limits;
    private final PaymentRouter router;
    private final AccountIdentifierScheme identifiers;

    /**
     * @param singleLimit     largest same-bank payment (pbp.payments.single-limit)
     * @param reviewThreshold payments at or above this are held for staff (pbp.payments.review-threshold)
     * @param achThreshold    the ACH value threshold; at or above it payments go by RTGS (pbp.market.jm.ach-threshold)
     * @param rtgsLimit       largest RTGS payment the bank sends for a customer (pbp.market.jm.rtgs.limit)
     * @param rtgsFee         the bank's fee for an outgoing RTGS payment, GCT included (pbp.market.jm.rtgs.fee)
     * @param rtgsOpens       when the bank starts sending RTGS payments, Jamaica time (pbp.market.jm.rtgs.opens)
     * @param rtgsCutOff      the bank's RTGS customer cut-off, Jamaica time (pbp.market.jm.rtgs.cut-off)
     * @param holidays        public holidays on which banks close (pbp.market.jm.holidays)
     * @param dailyCardSpend  spending control for new credit cards (pbp.credit.daily-limit)
     * @param identifiers     the account fields and their formats
     */
    public JmMarketPack(BigDecimal singleLimit, BigDecimal reviewThreshold, BigDecimal achThreshold,
                        BigDecimal rtgsLimit, BigDecimal rtgsFee, LocalTime rtgsOpens, LocalTime rtgsCutOff,
                        Collection<LocalDate> holidays, BigDecimal dailyCardSpend, JmAccountScheme identifiers) {
        this.calendar = BusinessCalendar.saturdaySunday(Set.copyOf(holidays));
        this.limits = new Limits(singleLimit, reviewThreshold, dailyCardSpend);
        this.identifiers = identifiers;
        // ACH takes amounts below the threshold: J$999,999.99 with a J$1,000,000.00 threshold.
        BigDecimal achLimit = achThreshold.subtract(new BigDecimal("0.01"));
        this.schemes = List.of(
                new PaymentScheme(INTERNAL, "Transfer within the bank", true, OperatingWindow.ALWAYS,
                        Optional.of(singleLimit), BigDecimal.ZERO),
                new PaymentScheme(ACH, "ACH transfer", false, OperatingWindow.ALWAYS,
                        Optional.of(achLimit), BigDecimal.ZERO),
                new PaymentScheme(RTGS, "RTGS transfer", false, OperatingWindow.businessHours(rtgsOpens, rtgsCutOff),
                        Optional.of(rtgsLimit), rtgsFee));
        this.router = new JmPaymentRouter(schemes, achThreshold);
    }

    /** Comma-separated values; blanks are ignored. */
    public static Set<LocalDate> parseHolidays(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(LocalDate::parse).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String code() {
        return "jm";
    }

    @Override
    public Currency currency() {
        return JMD;
    }

    @Override
    public ZoneId zone() {
        return JAMAICA;
    }

    @Override
    public Locale locale() {
        return Locale.forLanguageTag("en-JM");
    }

    @Override
    public BusinessCalendar calendar() {
        return calendar;
    }

    @Override
    public AccountIdentifierScheme accountIdentifiers() {
        return identifiers;
    }

    @Override
    public List<PaymentScheme> paymentSchemes() {
        return schemes;
    }

    @Override
    public PaymentRouter router() {
        return router;
    }

    @Override
    public Limits limits() {
        return limits;
    }

    /** No payee name check in Jamaica; the payment workflow skips the step. */
    @Override
    public Optional<NameCheck> nameCheck() {
        return Optional.empty();
    }

    /** Direct Debits are not in the first Jamaica pack (docs/design/market-packs.md, 4.4). */
    @Override
    public Optional<DirectDebitScheme> directDebits() {
        return Optional.empty();
    }
}
