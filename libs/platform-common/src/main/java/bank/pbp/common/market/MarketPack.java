package bank.pbp.common.market;

import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One country's banking rules (docs/design/market-packs.md). Exactly one pack is built into a deployment and
 * selected by {@code PBP_MARKET} (property {@code pbp.market}, default {@code uk}); the rest of the platform asks
 * the pack instead of knowing which country it runs in.
 *
 * <p>Stage 1 of the design moves the UK's rules into the UK pack step by step without changing behaviour. This
 * interface grows with those steps: payment routing (step 5), identity and reporting arrive with the code that
 * uses them.
 */
public interface MarketPack {

    /** Market code: "uk" today; "us", "co", "jm" and "ae" are designed, not built. */
    String code();

    /** The one currency accounts and payments use in this market. */
    Currency currency();

    /** Local time for cut-offs, due dates and business days. */
    ZoneId zone();

    /** Formatting for screens and messages. */
    Locale locale();

    /** Weekends and public holidays. */
    BusinessCalendar calendar();

    /** How an account is written and checked in this market. */
    AccountIdentifierScheme accountIdentifiers();

    /** The payment rails this market offers, same-bank first. */
    List<PaymentScheme> paymentSchemes();

    /** The scheme with this code, if the market has it. */
    default Optional<PaymentScheme> paymentScheme(String code) {
        return paymentSchemes().stream().filter(s -> s.code().equals(code)).findFirst();
    }

    /** Payment and card limits, in {@link #currency()}. */
    Limits limits();

    /** The market's payee name check (Confirmation of Payee in the UK), if it has one. */
    Optional<NameCheck> nameCheck();

    /** The market's Direct Debit scheme (Bacs in the UK), if the pack supports one. */
    Optional<DirectDebitScheme> directDebits();
}
