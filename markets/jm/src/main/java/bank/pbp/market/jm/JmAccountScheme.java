package bank.pbp.market.jm;

import bank.pbp.common.market.AccountIdentifier;
import bank.pbp.common.market.AccountIdentifierScheme;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Jamaican accounts: the payee's bank, branch and account number. Jamaica has no national account-number standard
 * with a check digit; each bank sets its own format. The patterns here are provisional and configurable
 * (pbp.market.jm.*-pattern) until they are confirmed against the ACH operator's rules and the client bank.
 */
public final class JmAccountScheme implements AccountIdentifierScheme {

    public static final String SCHEME = "JM_ACCOUNT";
    public static final String BANK_CODE = "bankCode";
    public static final String BRANCH_CODE = "branchCode";
    public static final String ACCOUNT_NUMBER = "accountNumber";

    private final List<Field> fields;

    JmAccountScheme(String bankPattern, String branchPattern, String accountPattern) {
        this.fields = List.of(
                new Field(BANK_CODE, "Bank code", bankPattern, "is not a bank code", Optional.empty()),
                new Field(BRANCH_CODE, "Branch", branchPattern, "is not a branch code", Optional.empty()),
                new Field(ACCOUNT_NUMBER, "Account number", accountPattern, "is not an account number",
                        Optional.empty()));
    }

    public static AccountIdentifier of(String bankCode, String branchCode, String accountNumber) {
        return new AccountIdentifier(SCHEME, Map.of(BANK_CODE, bankCode, BRANCH_CODE, branchCode,
                ACCOUNT_NUMBER, accountNumber));
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
            problems.put("scheme", "must be " + SCHEME + " in Jamaica");
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
