package bank.pbp.common.market;

import java.util.Map;

/**
 * An account as the market writes it: a scheme and its fields, e.g. scheme {@code UK_SORT_CODE} with
 * {@code {"sortCode": "040004", "accountNumber": "12345678"}}, or scheme {@code IBAN} with {@code {"iban": "AE07..."}}.
 */
public record AccountIdentifier(String scheme, Map<String, String> fields) {

    public AccountIdentifier {
        fields = Map.copyOf(fields);
    }

    public String field(String name) {
        return fields.get(name);
    }
}
