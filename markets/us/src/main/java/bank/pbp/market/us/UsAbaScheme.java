package bank.pbp.market.us;

import bank.pbp.common.market.AccountIdentifier;
import bank.pbp.common.market.AccountIdentifierScheme;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * US accounts: a 9-digit ABA routing number with its check digit, an account number of 4 to 17 digits, and
 * whether the account is checking or savings (ACH needs it). Whether the account exists is the core's job.
 */
public final class UsAbaScheme implements AccountIdentifierScheme {

    public static final String SCHEME = "US_ABA";
    public static final String ROUTING_NUMBER = "routingNumber";
    public static final String ACCOUNT_NUMBER = "accountNumber";
    public static final String ACCOUNT_TYPE = "accountType";

    private static final List<Field> FIELDS = List.of(
            new Field(ROUTING_NUMBER, "Routing number", "\\d{9}", "must be 9 digits", Optional.of("011000015")),
            new Field(ACCOUNT_NUMBER, "Account number", "\\d{4,17}", "must be 4 to 17 digits", Optional.of("000123456789")),
            new Field(ACCOUNT_TYPE, "Account type", "CHECKING|SAVINGS", "must be CHECKING or SAVINGS",
                    Optional.of("CHECKING")));

    public static AccountIdentifier of(String routingNumber, String accountNumber, String accountType) {
        return new AccountIdentifier(SCHEME, Map.of(ROUTING_NUMBER, routingNumber, ACCOUNT_NUMBER, accountNumber,
                ACCOUNT_TYPE, accountType));
    }

    /**
     * The ABA check: digits weighted 3, 7, 1 repeating, summed, must be a multiple of 10.
     * 011000015: 0·3 + 1·7 + 1·1 + 0·3 + 0·7 + 0·1 + 0·3 + 1·7 + 5·1 = 20.
     */
    public static boolean checksumValid(String routingNumber) {
        if (routingNumber == null || !routingNumber.matches("\\d{9}")) {
            return false;
        }
        int[] weights = {3, 7, 1};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (routingNumber.charAt(i) - '0') * weights[i % 3];
        }
        return sum % 10 == 0;
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
            problems.put("scheme", "must be " + SCHEME + " in the US");
            return problems;
        }
        for (Field f : FIELDS) {
            String value = id.field(f.name());
            if (value == null || !value.matches(f.pattern())) {
                problems.put(f.name(), f.hint());
            }
        }
        if (!problems.containsKey(ROUTING_NUMBER) && !checksumValid(id.field(ROUTING_NUMBER))) {
            problems.put(ROUTING_NUMBER, "is not a valid routing number (check digit)");
        }
        return problems;
    }
}
