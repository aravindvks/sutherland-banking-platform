package bank.pbp.common.market;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * A payment rail in a market: same-bank transfer, an instant scheme, a high-value scheme or a batch scheme.
 *
 * @param code    the platform's name for it, as stored on payments: INTERNAL, FPS, CHAPS in the UK
 * @param name    the name customers see, e.g. "Faster Payments"
 * @param instant true when the payee is credited in seconds
 * @param window  when this bank can send on it
 * @param limit   largest single payment in the market's currency; empty when the scheme sets none for the bank
 * @param fee     the bank's tariff for sending one; zero when free
 */
public record PaymentScheme(String code, String name, boolean instant, OperatingWindow window,
                            Optional<BigDecimal> limit, BigDecimal fee) {

    public PaymentScheme {
        limit = limit == null ? Optional.empty() : limit;
        fee = fee == null ? BigDecimal.ZERO : fee;
    }

    public boolean allows(BigDecimal amount) {
        return limit.map(l -> amount.compareTo(l) <= 0).orElse(true);
    }
}
