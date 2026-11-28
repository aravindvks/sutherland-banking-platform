package bank.pbp.market.us;

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
 * The US market. Upcoming: built and unit-tested, not yet run end to end on the simulators.
 *
 * <p>Network facts as published (November 2026): RTP and FedNow each allow up to US$10 million per payment and
 * run every day; Fedwire runs from 21:00 ET the day before to 19:00 ET on Federal Reserve business days, with
 * customer transfers due by 18:45 ET (the Federal Reserve has announced a move to six days a week). A bank sets its
 * own, lower limits and an earlier customer cut-off. Those, the wire fee and the holiday list are configuration
 * (UsMarketAutoConfiguration), confirmed with the client bank before the pack is called ready.
 */
public final class UsMarketPack implements MarketPack {

    public static final String INTERNAL = "INTERNAL";
    public static final String RTP = "RTP";
    public static final String FEDNOW = "FEDNOW";
    public static final String ACH = "ACH";
    public static final String WIRE = "WIRE";

    public static final ZoneId EASTERN = ZoneId.of("America/New_York");
    public static final Currency USD = Currency.getInstance("USD");

    private final BusinessCalendar calendar;
    private final List<PaymentScheme> schemes;
    private final List<PaymentScheme> choices;
    private final Limits limits;
    private final PaymentRouter router;
    private final AccountIdentifierScheme identifiers = new UsAbaScheme();

    /**
     * @param singleLimit       largest same-bank or ACH payment (pbp.payments.single-limit)
     * @param reviewThreshold   payments at or above this are held for staff (pbp.payments.review-threshold)
     * @param instantLimit      the bank's limit for RTP and FedNow (pbp.market.us.instant-limit)
     * @param wireLimit         largest wire (pbp.market.us.wire.limit)
     * @param wireFee           the bank's fee for a wire (pbp.market.us.wire.fee)
     * @param wireOpens         when the bank starts sending wires, Eastern time (pbp.market.us.wire.opens)
     * @param wireCutOff        the bank's customer cut-off for wires, Eastern time (pbp.market.us.wire.cut-off)
     * @param holidays          Federal Reserve holidays on which the Reserve Banks close (pbp.market.us.holidays)
     * @param dailyCardSpend    spending control for new credit cards (pbp.credit.daily-limit)
     * @param rtpUnreachable    routing numbers not on RTP (pbp.market.us.rtp-unreachable)
     * @param fedNowUnreachable routing numbers not on FedNow (pbp.market.us.fednow-unreachable)
     */
    public UsMarketPack(BigDecimal singleLimit, BigDecimal reviewThreshold, BigDecimal instantLimit,
                        BigDecimal wireLimit, BigDecimal wireFee, LocalTime wireOpens, LocalTime wireCutOff,
                        Collection<LocalDate> holidays, BigDecimal dailyCardSpend,
                        Set<String> rtpUnreachable, Set<String> fedNowUnreachable) {
        this.calendar = BusinessCalendar.saturdaySunday(Set.copyOf(holidays));
        this.limits = new Limits(singleLimit, reviewThreshold, dailyCardSpend);
        PaymentScheme rtp = new PaymentScheme(RTP, "Instant payment (RTP)", true, OperatingWindow.ALWAYS,
                Optional.of(instantLimit), BigDecimal.ZERO);
        PaymentScheme ach = new PaymentScheme(ACH, "ACH transfer", false, OperatingWindow.ALWAYS,
                Optional.of(singleLimit), BigDecimal.ZERO);
        PaymentScheme wire = new PaymentScheme(WIRE, "Wire transfer", false,
                OperatingWindow.businessHours(wireOpens, wireCutOff), Optional.of(wireLimit), wireFee);
        this.schemes = List.of(
                new PaymentScheme(INTERNAL, "Transfer within the bank", true, OperatingWindow.ALWAYS,
                        Optional.of(singleLimit), BigDecimal.ZERO),
                rtp,
                new PaymentScheme(FEDNOW, "Instant payment (FedNow)", true, OperatingWindow.ALWAYS,
                        Optional.of(instantLimit), BigDecimal.ZERO),
                ach,
                wire);
        // The customer asks for an instant payment; the router sends it by RTP or FedNow, whichever reaches the payee.
        this.choices = List.of(new PaymentScheme(RTP, "Instant payment", true, OperatingWindow.ALWAYS,
                Optional.of(instantLimit), BigDecimal.ZERO), ach, wire);
        this.router = new UsPaymentRouter(schemes, rtpUnreachable, fedNowUnreachable);
    }

    /** Comma-separated values (ISO dates, routing numbers); blanks are ignored. */
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
        return "us";
    }

    @Override
    public Currency currency() {
        return USD;
    }

    @Override
    public ZoneId zone() {
        return EASTERN;
    }

    @Override
    public Locale locale() {
        return Locale.US;
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
    public List<PaymentScheme> customerChoices() {
        return choices;
    }

    @Override
    public PaymentRouter router() {
        return router;
    }

    @Override
    public Limits limits() {
        return limits;
    }

    /** No national payee name check in the US; the payment workflow skips the step. */
    @Override
    public Optional<NameCheck> nameCheck() {
        return Optional.empty();
    }

    /** ACH debits, authorised by the customer; consumer rights under Regulation E. */
    @Override
    public Optional<DirectDebitScheme> directDebits() {
        return Optional.of(new DirectDebitScheme("ACH debit", "Company ID", "Regulation E"));
    }
}
