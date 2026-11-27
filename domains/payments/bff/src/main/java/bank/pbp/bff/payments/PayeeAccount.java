package bank.pbp.bff.payments;

import bank.pbp.common.market.AccountIdentifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A payee's account in a request, in either shape (docs/design/market-packs.md, Stage 1 step 3):
 * <ul>
 *   <li>{@code account}: the market's identifier (scheme and fields), the shape every market uses;</li>
 *   <li>{@code sortCode} and {@code accountNumber}: the original shape, which is the UK's. Still accepted so the
 *       web app and scripts keep working while they move over; removed in step 8.</li>
 * </ul>
 */
public interface PayeeAccount {

    /** The scheme the original request shape means. */
    String LEGACY_SCHEME = AccountIdentifier.ORIGINAL_SHAPE_SCHEME;

    String sortCode();

    String accountNumber();

    AccountBody account();

    /** True when the request uses the original sortCode/accountNumber fields. */
    default boolean legacyShape() {
        return account() == null;
    }

    /** The account as one identifier, whichever shape the request used. */
    default AccountIdentifier identifier() {
        if (account() != null) {
            return account().toIdentifier();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (sortCode() != null) {
            fields.put("sortCode", sortCode());
        }
        if (accountNumber() != null) {
            fields.put("accountNumber", accountNumber());
        }
        return new AccountIdentifier(LEGACY_SCHEME, fields);
    }

    /**
     * The sort code that commands, events and the core still carry until step 4 widens them; for a UK account in
     * either request shape.
     */
    default String effectiveSortCode() {
        return identifier().field("sortCode");
    }

    /** The account number, as {@link #effectiveSortCode()}. */
    default String effectiveAccountNumber() {
        return identifier().field("accountNumber");
    }
}
