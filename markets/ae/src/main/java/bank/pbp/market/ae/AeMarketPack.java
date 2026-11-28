package bank.pbp.market.ae;

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
 * The UAE market. Upcoming: built and unit-tested, not yet run end to end on the simulators.
 *
 * <p>As published (November 2026): Aani, run by Al Etihad Payments (a Central Bank of the UAE subsidiary), pays
 * instantly around the clock, up to AED 50,000 per payment at the platform level, with each bank setting its own,
 * often lower, limits and fees; UAEFTS, the Central Bank's RTGS, carries larger and time-critical payments in
 * business hours. The bank's limits, fees and cut-off, and the holiday list, are configuration
 * (AeMarketAutoConfiguration), confirmed with the client bank before the pack is called ready.
 */
public final class AeMarketPack implements MarketPack {

    public static final String INTERNAL = "INTERNAL";
    public static final String AANI = "AANI";
    public static final String UAEFTS = "UAEFTS";

    public static final ZoneId UAE = ZoneId.of("Asia/Dubai");
    public static final Currency AED = Currency.getInstance("AED");

    private final BusinessCalendar calendar;
    private final List<PaymentScheme> schemes;
    private final Limits limits;
    private final PaymentRouter router;
    private final AccountIdentifierScheme identifiers = new AeIbanScheme();

    /**
     * @param singleLimit     largest same-bank payment (pbp.payments.single-limit)
     * @param reviewThreshold payments at or above this are held for staff (pbp.payments.review-threshold)
     * @param aaniLimit       the bank's Aani limit per payment, at most the platform's AED 50,000 (pbp.market.ae.aani.limit)
     * @param aaniFee         the bank's Aani fee, if any (pbp.market.ae.aani.fee)
     * @param ftsLimit        largest UAEFTS payment the bank sends for a customer (pbp.market.ae.uaefts.limit)
     * @param ftsFee          the bank's UAEFTS fee (pbp.market.ae.uaefts.fee)
     * @param ftsOpens        when the bank starts sending UAEFTS payments, UAE time (pbp.market.ae.uaefts.opens)
     * @param ftsCutOff       the bank's UAEFTS customer cut-off, UAE time (pbp.market.ae.uaefts.cut-off)
     * @param holidays        public holidays (pbp.market.ae.holidays)
     * @param dailyCardSpend  spending control for new credit cards (pbp.credit.daily-limit)
     * @param aaniUnreachable bank codes not on Aani (pbp.market.ae.aani-unreachable)
     */
    public AeMarketPack(BigDecimal singleLimit, BigDecimal reviewThreshold, BigDecimal aaniLimit, BigDecimal aaniFee,
                        BigDecimal ftsLimit, BigDecimal ftsFee, LocalTime ftsOpens, LocalTime ftsCutOff,
                        Collection<LocalDate> holidays, BigDecimal dailyCardSpend, Set<String> aaniUnreachable) {
        if (aaniLimit.compareTo(new BigDecimal("50000.00")) > 0) {
            throw new IllegalArgumentException("Aani limit " + aaniLimit + " is above the platform's AED 50,000");
        }
        // Saturday and Sunday since January 2022.
        this.calendar = BusinessCalendar.saturdaySunday(Set.copyOf(holidays));
        this.limits = new Limits(singleLimit, reviewThreshold, dailyCardSpend);
        this.schemes = List.of(
                new PaymentScheme(INTERNAL, "Transfer within the bank", true, OperatingWindow.ALWAYS,
                        Optional.of(singleLimit), BigDecimal.ZERO),
                new PaymentScheme(AANI, "Aani instant payment", true, OperatingWindow.ALWAYS,
                        Optional.of(aaniLimit), aaniFee),
                new PaymentScheme(UAEFTS, "Bank transfer (UAEFTS)", false,
                        OperatingWindow.businessHours(ftsOpens, ftsCutOff), Optional.of(ftsLimit), ftsFee));
        this.router = new AePaymentRouter(schemes, aaniUnreachable);
    }

    /** Comma-separated values; blanks are ignored. */
    public static Set<String> parseList(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Set<LocalDate> parseHolidays(String commaSeparated) {
        return parseList(commaSeparated).stream().map(LocalDate::parse).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String code() {
        return "ae";
    }

    @Override
    public Currency currency() {
        return AED;
    }

    @Override
    public ZoneId zone() {
        return UAE;
    }

    @Override
    public Locale locale() {
        return Locale.forLanguageTag("en-AE");
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

    /**
     * None yet. Aani shows the payee's name for proxy (mobile-number) payments; that lookup is added with the proxy,
     * mapped to the same four results as Confirmation of Payee (docs/design/market-packs.md, 4.3).
     */
    @Override
    public Optional<NameCheck> nameCheck() {
        return Optional.empty();
    }

    /** UAEDDS mandates (docs/design/market-packs.md, 4.4). */
    @Override
    public Optional<DirectDebitScheme> directDebits() {
        return Optional.of(new DirectDebitScheme("UAE Direct Debit System (UAEDDS)", "Originator ID", ""));
    }
}
