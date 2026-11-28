package bank.pbp.market.co;

import bank.pbp.common.market.AccountIdentifier;
import bank.pbp.common.market.AccountIdentifierScheme;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Colombian accounts: the payee's institution, account number and account type (savings or current). Formats vary by
 * institution, so the patterns are provisional and configurable (pbp.market.co.*-pattern). Bre-B keys (llaves: ID
 * number, mobile number, e-mail or alphanumeric key) are not account identifiers and come with the Bre-B lookup.
 */
public final class CoAccountScheme implements AccountIdentifierScheme {

    public static final String SCHEME = "CO_ACCOUNT";
    public static final String BANK_CODE = "bankCode";
    public static final String ACCOUNT_NUMBER = "accountNumber";
    public static final String ACCOUNT_TYPE = "accountType";

    private final List<Field> fields;

    CoAccountScheme(String bankPattern, String accountPattern) {
        this.fields = List.of(
                new Field(BANK_CODE, "Bank code", bankPattern, "is not a bank code", Optional.empty()),
                new Field(ACCOUNT_NUMBER, "Account number", accountPattern, "is not an account number", Optional.empty()),
                new Field(ACCOUNT_TYPE, "Account type", "SAVINGS|CURRENT", "must be SAVINGS or CURRENT",
                        Optional.of("SAVINGS")));
    }

    public static AccountIdentifier of(String bankCode, String accountNumber, String accountType) {
        return new AccountIdentifier(SCHEME, Map.of(BANK_CODE, bankCode, ACCOUNT_NUMBER, accountNumber,
                ACCOUNT_TYPE, accountType));
    }

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Map<String, String> problems(AccountIdentifier id) {
        Map<String, String> problems = new LinkedHashMap<>();
        if (!SCHEME.equals(id.scheme())) {
            problems.put("scheme", "must be " + SCHEME + " in Colombia");
            return problems;
        }
        for (Field f : fields) {
            String value = id.field(f.name());
            if (value == null || !value.matches(f.pattern())) {
                problems.put(f.name(), f.hint());
            }
        }
        return problems;
    }
}
