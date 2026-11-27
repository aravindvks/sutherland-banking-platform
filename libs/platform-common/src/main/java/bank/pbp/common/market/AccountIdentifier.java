package bank.pbp.common.market;

import java.util.Map;

/**
 * An account as the market writes it: a scheme and its fields, e.g. scheme {@code UK_SORT_CODE} with
 * {@code {"sortCode": "040004", "accountNumber": "12345678"}}, or scheme {@code IBAN} with {@code {"iban": "AE07..."}}.
 */
public record AccountIdentifier(String scheme, Map<String, String> fields) {

    /**
     * The scheme of the original sortCode/accountNumber shape of requests, commands and rows, which is the UK's.
     * Used to read messages and rows written before identifiers existed; removed with that shape (Stage 1 step 8).
     */
    public static final String ORIGINAL_SHAPE_SCHEME = "UK_SORT_CODE";

    public AccountIdentifier {
        fields = Map.copyOf(fields);
    }

    /** The identifier for the original sortCode/accountNumber shape; null when neither is present. */
    public static AccountIdentifier fromOriginalShape(String sortCode, String accountNumber) {
        if (sortCode == null && accountNumber == null) {
            return null;
        }
        Map<String, String> f = new java.util.LinkedHashMap<>();
        if (sortCode != null) {
            f.put("sortCode", sortCode);
        }
        if (accountNumber != null) {
            f.put("accountNumber", accountNumber);
        }
        return new AccountIdentifier(ORIGINAL_SHAPE_SCHEME, f);
    }

    /**
     * Reads {@code {"scheme": ..., "fields": {...}}} from a parsed JSON message; null when absent or incomplete.
     * Field values are kept as text.
     */
    public static AccountIdentifier fromJson(Object value) {
        if (!(value instanceof Map<?, ?> m) || !(m.get("scheme") instanceof String scheme)
                || !(m.get("fields") instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, String> f = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && v != null) {
                f.put(k.toString(), v.toString());
            }
        });
        return new AccountIdentifier(scheme, f);
    }

    /** As messages and JSON columns carry it. */
    public Map<String, Object> toJson() {
        return Map.of("scheme", scheme, "fields", fields);
    }

    public String field(String name) {
        return fields.get(name);
    }
}
