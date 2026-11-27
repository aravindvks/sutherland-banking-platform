package bank.pbp.common.market;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** How accounts are written and checked in one market. */
public interface AccountIdentifierScheme {

    /** The scheme name carried on every {@link AccountIdentifier}, e.g. {@code UK_SORT_CODE}. */
    String scheme();

    /** The fields a form asks for, in order. */
    List<Field> fields();

    /**
     * Problems with this identifier, field name to message; empty when it is valid. Checks format and any
     * check digits; whether the account exists is the core's or the name check's job.
     */
    Map<String, String> problems(AccountIdentifier id);

    default boolean isValid(AccountIdentifier id) {
        return problems(id).isEmpty();
    }

    /**
     * One field of an account identifier.
     *
     * @param name    key in {@link AccountIdentifier#fields()}
     * @param label   what the screen shows, e.g. "Sort code"
     * @param pattern regular expression the value must match
     * @param hint    the message when it does not, e.g. "must be 6 digits"
     * @param example a placeholder for forms
     */
    record Field(String name, String label, String pattern, String hint, Optional<String> example) {
    }
}
