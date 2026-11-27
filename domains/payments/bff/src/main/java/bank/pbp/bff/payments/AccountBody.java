package bank.pbp.bff.payments;

import bank.pbp.common.market.AccountIdentifier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * An account in the market's own terms (docs/design/market-packs.md, Stage 1 step 3), e.g.
 * {@code {"scheme": "UK_SORT_CODE", "fields": {"sortCode": "040004", "accountNumber": "12345678"}}}.
 * The market pack checks the fields ({@link ValidAccount}).
 */
public record AccountBody(@NotBlank String scheme, @NotNull Map<String, String> fields) {

    /** The identifier; fields sent as null are left out, so the market pack reports them as missing. */
    public AccountIdentifier toIdentifier() {
        Map<String, String> present = new java.util.LinkedHashMap<>();
        if (fields != null) {
            fields.forEach((k, v) -> {
                if (k != null && v != null) {
                    present.put(k, v);
                }
            });
        }
        return new AccountIdentifier(scheme, present);
    }
}
