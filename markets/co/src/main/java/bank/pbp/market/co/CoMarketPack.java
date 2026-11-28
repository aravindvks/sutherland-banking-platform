package bank.pbp.market.co;

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
 * The Colombia market. Upcoming: built and unit-tested, not yet run end to end on the simulators.
 *
 * <p>As published (November 2026): Bre-B, the Banco de la República's instant payment system, runs every day of the
 * year; its maximum per payment is 1,000 UVB (basic value units), COP 12,110,000 in 2026, and each institution may
 * set a lower limit. The Sistema de Cuentas de Depósito (CUD) is the Banco de la República's high-value system; ACH
 * Colombia and ACH CENIT clear batch transfers. The bank's limits, fees and CUD hours, the account formats and the
 * holiday list are configuration (CoMarketAutoConfiguration), confirmed with the client bank before the pack is
 * called ready.
 */
public final class CoMarketPack implements MarketPack {

    public static final String INTERNAL = "INTERNAL";
    public static final String BREB = "BREB";
    public static final String ACH = "ACH";
    public static final String CUD = "CUD";

    public static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");
    public static final Currency COP = Currency.getInstance("COP");

    private final BusinessCalendar calendar;
    private final List<PaymentScheme> schemes;
    private final Limits limits;
    private final PaymentRouter router;
    private final AccountIdentifierScheme identifiers;

    /**
     * @param singleLimit      largest same-bank or ACH payment (pbp.payments.single-limit)
     * @param reviewThreshold  payments at or above this are held for staff (pbp.payments.review-threshold)
     * @param breBLimit        the bank's Bre-B limit per payment (pbp.market.co.breb.limit)
     * @param breBSystemLimit  Bre-B's own maximum, 1,000 UVB for the year (pbp.market.co.breb.system-limit)
     * @param cudLimit         largest CUD transfer the bank sends for a customer (pbp.market.co.cud.limit)
     * @param cudFee           the bank's fee for a CUD transfer (pbp.market.co.cud.fee)
     * @param cudOpens         when the bank starts sending CUD transfers, Colombia time (pbp.market.co.cud.opens)
     * @param cudCutOff        the bank's CUD customer cut-off, Colombia time (pbp.market.co.cud.cut-off)
     * @param holidays         public holidays (pbp.market.co.holidays)
     * @param dailyCardSpend   spending control for new credit cards (pbp.credit.daily-limit)
     * @param breBUnreachable  bank codes not on Bre-B (pbp.market.co.breb-unreachable)
     * @param identifiers      the account fields and their formats
     */
    public CoMarketPack(BigDecimal singleLimit, BigDecimal reviewThreshold, BigDecimal breBLimit,
                        BigDecimal breBSystemLimit, BigDecimal cudLimit, BigDecimal cudFee, LocalTime cudOpens,
                        LocalTime cudCutOff, Collection<LocalDate> holidays, BigDecimal dailyCardSpend,
                        Set<String> breBUnreachable, CoAccountScheme identifiers) {
        if (breBLimit.compareTo(breBSystemLimit) > 0) {
            throw new IllegalArgumentException("Bre-B limit " + breBLimit + " is above Bre-B's own maximum "
                    + breBSystemLimit);
        }
        this.calendar = BusinessCalendar.saturdaySunday(Set.copyOf(holidays));
        this.limits = new Limits(singleLimit, reviewThreshold, dailyCardSpend);
        this.identifiers = identifiers;
        this.schemes = List.of(
                new PaymentScheme(INTERNAL, "Transfer within the bank", true, OperatingWindow.ALWAYS,
                        Optional.of(singleLimit), BigDecimal.ZERO),
                new PaymentScheme(BREB, "Bre-B instant payment", true, OperatingWindow.ALWAYS,
                        Optional.of(breBLimit), BigDecimal.ZERO),
                new PaymentScheme(ACH, "ACH transfer", false, OperatingWindow.ALWAYS,
                        Optional.of(singleLimit), BigDecimal.ZERO),
                new PaymentScheme(CUD, "High-value transfer (CUD)", false,
                        OperatingWindow.businessHours(cudOpens, cudCutOff), Optional.of(cudLimit), cudFee));
        this.router = new CoPaymentRouter(schemes, breBUnreachable);
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
        return "co";
    }

    @Override
    public Currency currency() {
        return COP;
    }

    @Override
    public ZoneId zone() {
        return COLOMBIA;
    }

    @Override
    public Locale locale() {
        return Locale.forLanguageTag("es-CO");
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
     * None yet. Bre-B shows the payee's masked name when a key is resolved; that comes with the Bre-B key lookup,
     * mapped to the same four results as Confirmation of Payee (docs/design/market-packs.md, 4.3).
     */
    @Override
    public Optional<NameCheck> nameCheck() {
        return Optional.empty();
    }

    /** Direct Debits are not in the first Colombia pack (docs/design/market-packs.md, 4.4). */
    @Override
    public Optional<DirectDebitScheme> directDebits() {
        return Optional.empty();
    }
}
