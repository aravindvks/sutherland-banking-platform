package bank.pbp.common.market;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The deployment's market for code that Spring cannot inject into: Temporal workflow code, which Temporal
 * constructs itself. Set once at start-up from the market pack bean ({@link MarketGuard}); constant for the life
 * of the process, so workflow replay stays deterministic. Until it is set (unit tests without Spring) it answers
 * with the UK, exactly as the UK pack does.
 */
public final class ActiveMarket {

    /** What workflow code needs from the pack, copied once so it never changes under a running workflow. */
    private record Facts(ZoneId zone, boolean nameCheck, Set<String> withHours, Set<String> instant,
                         Map<String, String> names, String currencySymbol) {
    }

    private static final Facts UK = new Facts(ZoneId.of("Europe/London"), true, Set.of("CHAPS"),
            Set.of("INTERNAL", "FPS"), Map.of("INTERNAL", "Transfer within the bank", "FPS", "Faster Payments",
            "CHAPS", "CHAPS"), "£");

    private static volatile Facts facts = UK;

    private ActiveMarket() {
    }

    /** The market's local time zone: cut-offs, due dates and run times. */
    public static ZoneId zone() {
        return facts.zone();
    }

    /** Whether the market has a payee name check (Confirmation of Payee in the UK); without one the step is skipped. */
    public static boolean hasNameCheck() {
        return facts.nameCheck();
    }

    /** Whether payments on this scheme wait for its opening hours (CHAPS in the UK). */
    public static boolean hasHours(String schemeCode) {
        return schemeCode != null && facts.withHours().contains(schemeCode);
    }

    /** Whether the scheme credits the payee in seconds (Faster Payments in the UK; not CHAPS). */
    public static boolean isInstant(String schemeCode) {
        return schemeCode == null || facts.instant().contains(schemeCode);
    }

    /** The scheme's name for customers and staff ("CHAPS"); the code itself if the market has no such scheme. */
    public static String schemeName(String schemeCode) {
        return facts.names().getOrDefault(schemeCode, schemeCode);
    }

    /** The currency's symbol in the market's language: "£" in the UK. */
    public static String currencySymbol() {
        return facts.currencySymbol();
    }

    /** Back to the UK defaults (tests). */
    static void reset() {
        facts = UK;
    }

    static void set(MarketPack pack) {
        // Null-safe so a partly stubbed pack in a test falls back to the UK's values rather than failing.
        Locale locale = pack.locale() == null ? Locale.UK : pack.locale();
        Currency currency = pack.currency();
        java.util.List<PaymentScheme> schemes = pack.paymentSchemes() == null ? java.util.List.of() : pack.paymentSchemes();
        facts = new Facts(pack.zone() == null ? UK.zone() : pack.zone(),
                pack.nameCheck() != null && pack.nameCheck().isPresent(),
                schemes.stream().filter(s -> !s.window().always()).map(PaymentScheme::code)
                        .collect(Collectors.toUnmodifiableSet()),
                schemes.stream().filter(PaymentScheme::instant).map(PaymentScheme::code)
                        .collect(Collectors.toUnmodifiableSet()),
                schemes.stream().collect(Collectors.toUnmodifiableMap(PaymentScheme::code, PaymentScheme::name)),
                currency == null ? UK.currencySymbol() : currency.getSymbol(locale));
    }

}
