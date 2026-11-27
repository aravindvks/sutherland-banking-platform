package bank.pbp.market.uk;

import bank.pbp.common.market.AccountIdentifier;
import bank.pbp.common.market.AccountIdentifierScheme;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** UK accounts: a 6-digit sort code and an 8-digit account number, checked as the payment API checks them today. */
public final class UkSortCodeScheme implements AccountIdentifierScheme {

    public static final String SCHEME = "UK_SORT_CODE";
    public static final String SORT_CODE = "sortCode";
    public static final String ACCOUNT_NUMBER = "accountNumber";

    private static final List<Field> FIELDS = List.of(
            new Field(SORT_CODE, "Sort code", "\\d{6}", "must be 6 digits", Optional.of("040004")),
            new Field(ACCOUNT_NUMBER, "Account number", "\\d{8}", "must be 8 digits", Optional.of("12345678")));

    /** The UK identifier for a sort code and account number. */
    public static AccountIdentifier of(String sortCode, String accountNumber) {
        return new AccountIdentifier(SCHEME, Map.of(SORT_CODE, sortCode, ACCOUNT_NUMBER, accountNumber));
    }

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public List<Field> fields() {
        return FIELDS;
    }

    @Override
    public Map<String, String> problems(AccountIdentifier id) {
        Map<String, String> problems = new LinkedHashMap<>();
        if (!SCHEME.equals(id.scheme())) {
            problems.put("scheme", "must be " + SCHEME + " in the UK");
            return problems;
        }
        for (Field f : FIELDS) {
            String value = id.field(f.name());
            if (value == null || !value.matches(f.pattern())) {
                problems.put(f.name(), f.hint());
            }
        }
        return problems;
    }
}
