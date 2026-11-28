package bank.pbp.market.ae;

import bank.pbp.common.market.AccountIdentifier;
import bank.pbp.common.market.AccountIdentifierScheme;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * UAE accounts: the IBAN. 23 characters: "AE", 2 check digits, a 3-digit bank code and a 16-digit account number,
 * checked by the ISO 13616 mod-97 rule. Aani's mobile-number proxy is not an account identifier and comes later.
 */
public final class AeIbanScheme implements AccountIdentifierScheme {

    public static final String SCHEME = "IBAN";
    public static final String IBAN = "iban";

    private static final List<Field> FIELDS = List.of(
            new Field(IBAN, "IBAN", "AE\\d{21}", "must be AE followed by 21 digits", Optional.of("AE070331234567890123456")));

    public static AccountIdentifier of(String iban) {
        return new AccountIdentifier(SCHEME, Map.of(IBAN, iban));
    }

    /** The receiving bank's 3-digit code: characters 5 to 7 of the IBAN. */
    public static String bankCode(String iban) {
        return iban != null && iban.length() >= 7 ? iban.substring(4, 7) : null;
    }

    /** ISO 13616: move the first four characters to the end, letters to numbers (A = 10 … Z = 35), mod 97 = 1. */
    public static boolean checksumValid(String iban) {
        if (iban == null || !iban.matches("[A-Z]{2}\\d{2}[A-Z0-9]+")) {
            return false;
        }
        String moved = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : moved.toCharArray()) {
            digits.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
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
            problems.put("scheme", "must be " + SCHEME + " in the UAE");
            return problems;
        }
        String iban = id.field(IBAN);
        if (iban == null || !iban.matches(FIELDS.get(0).pattern())) {
            problems.put(IBAN, FIELDS.get(0).hint());
        } else if (!checksumValid(iban)) {
            problems.put(IBAN, "is not a valid IBAN (check digits)");
        }
        return problems;
    }
}
