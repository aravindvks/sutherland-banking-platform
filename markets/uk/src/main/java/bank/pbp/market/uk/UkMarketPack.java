package bank.pbp.market.uk;

import bank.pbp.common.market.AccountIdentifierScheme;
import bank.pbp.common.market.BusinessCalendar;
import bank.pbp.common.market.DirectDebitScheme;
import bank.pbp.common.market.Limits;
import bank.pbp.common.market.MarketPack;
import bank.pbp.common.market.NameCheck;
import bank.pbp.common.market.OperatingWindow;
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
 * The UK market: today's rules, moved behind {@link MarketPack} without changing them. Values come from the same
 * configuration keys the orchestrator reads today (pbp.payments.*, pbp.credit.daily-limit), with the same defaults.
 */
public final class UkMarketPack implements MarketPack {

    public static final ZoneId UK = ZoneId.of("Europe/London");
    public static final Currency GBP = Currency.getInstance("GBP");

    private final BusinessCalendar calendar;
    private final List<PaymentScheme> schemes;
    private final Limits limits;
    private final AccountIdentifierScheme identifiers = new UkSortCodeScheme();

    /**
     * @param singleLimit     largest same-bank or Faster Payment (pbp.payments.single-limit)
     * @param reviewThreshold payments at or above this are held for staff (pbp.payments.review-threshold)
     * @param chapsLimit      largest CHAPS payment (pbp.payments.chaps.limit)
     * @param chapsFee        the bank's CHAPS fee (pbp.payments.chaps.fee)
     * @param chapsOpens      the bank's CHAPS opening time, UK time (pbp.payments.chaps.opens)
     * @param chapsCutOff     the bank's CHAPS cut-off, UK time (pbp.payments.chaps.cut-off)
     * @param holidays        England and Wales bank holidays (pbp.payments.chaps.holidays)
     * @param dailyCardSpend  spending control for new credit cards (pbp.credit.daily-limit)
     */
    public UkMarketPack(BigDecimal singleLimit, BigDecimal reviewThreshold, BigDecimal chapsLimit, BigDecimal chapsFee,
                        LocalTime chapsOpens, LocalTime chapsCutOff, Collection<LocalDate> holidays,
                        BigDecimal dailyCardSpend) {
        this.calendar = BusinessCalendar.saturdaySunday(Set.copyOf(holidays));
        this.limits = new Limits(singleLimit, reviewThreshold, dailyCardSpend);
        this.schemes = List.of(
                new PaymentScheme("INTERNAL", "Transfer within the bank", true, OperatingWindow.ALWAYS,
                        Optional.of(singleLimit), BigDecimal.ZERO),
                new PaymentScheme("FPS", "Faster Payments", true, OperatingWindow.ALWAYS,
                        Optional.of(singleLimit), BigDecimal.ZERO),
                new PaymentScheme("CHAPS", "CHAPS", false, OperatingWindow.businessHours(chapsOpens, chapsCutOff),
                        Optional.of(chapsLimit), chapsFee));
    }

    /** Parses the comma-separated ISO dates used by pbp.payments.chaps.holidays; blanks are ignored. */
    public static Set<LocalDate> parseHolidays(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(commaSeparated.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(LocalDate::parse)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String code() {
        return "uk";
    }

    @Override
    public Currency currency() {
        return GBP;
    }

    @Override
    public ZoneId zone() {
        return UK;
    }

    @Override
    public Locale locale() {
        return Locale.UK;
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
    public Limits limits() {
        return limits;
    }

    @Override
    public Optional<NameCheck> nameCheck() {
        return Optional.of(new NameCheck("Confirmation of Payee"));
    }

    @Override
    public Optional<DirectDebitScheme> directDebits() {
        return Optional.of(new DirectDebitScheme("Bacs Direct Debit", "Service user number", "Direct Debit Guarantee"));
    }
}
