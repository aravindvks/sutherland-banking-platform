package bank.pbp.bff;

import bank.pbp.common.market.AccountIdentifierScheme;
import bank.pbp.common.market.MarketPack;
import bank.pbp.common.market.PaymentScheme;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deployment's market (docs/design/market-packs.md): currency, language, local time, account fields, payment
 * schemes, limits, name check and Direct Debits. The web app formats money and dates, and labels its forms, from
 * this instead of assuming the UK. Money as decimal strings.
 */
@RestController
public class MarketController {

    private final Map<String, Object> market;

    public MarketController(MarketPack pack) {
        this.market = describe(pack);
    }

    @GetMapping("/v1/market")
    public Map<String, Object> market() {
        return market;
    }

    static Map<String, Object> describe(MarketPack pack) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", pack.code());
        m.put("currency", pack.currency().getCurrencyCode());
        m.put("locale", pack.locale().toLanguageTag());
        m.put("zone", pack.zone().getId());
        AccountIdentifierScheme ids = pack.accountIdentifiers();
        m.put("accountIdentifier", Map.of("scheme", ids.scheme(), "fields", ids.fields().stream().map(f -> {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", f.name());
            field.put("label", f.label());
            field.put("pattern", f.pattern());
            field.put("hint", f.hint());
            f.example().ifPresent(e -> field.put("example", e));
            return field;
        }).toList()));
        m.put("paymentSchemes", pack.paymentSchemes().stream().map(MarketController::scheme).toList());
        m.put("limits", Map.of(
                "singlePayment", money(pack.limits().singlePayment()),
                "reviewThreshold", money(pack.limits().reviewThreshold()),
                "dailyCardSpend", money(pack.limits().dailyCardSpend())));
        pack.nameCheck().ifPresent(n -> m.put("nameCheck", Map.of("name", n.name())));
        pack.directDebits().ifPresent(d -> m.put("directDebits", Map.of("name", d.name(),
                "originatorIdName", d.originatorIdName(), "guarantee", d.guarantee())));
        return Map.copyOf(m);
    }

    private static Map<String, Object> scheme(PaymentScheme s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.code());
        m.put("name", s.name());
        m.put("instant", s.instant());
        m.put("alwaysOpen", s.window().always());
        if (!s.window().always()) {
            m.put("opens", s.window().opens().toString());
            m.put("cutOff", s.window().cutOff().toString());
        }
        s.limit().ifPresent(l -> m.put("limit", money(l)));
        m.put("fee", money(s.fee()));
        return m;
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString();
    }

    /** For tests: the scheme codes in order. */
    static List<String> schemeCodes(Map<String, Object> market) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> schemes = (List<Map<String, Object>>) market.get("paymentSchemes");
        return schemes.stream().map(s -> String.valueOf(s.get("code"))).toList();
    }
}
