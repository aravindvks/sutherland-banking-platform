package bank.pbp.market.jm;

import bank.pbp.common.market.PaymentIntent;
import bank.pbp.common.market.PaymentRouter;
import bank.pbp.common.market.PaymentScheme;
import java.math.BigDecimal;
import java.util.List;

/**
 * Jamaican routing (docs/design/market-packs.md, 4.2). Jamaica has no national instant payment system.
 * <ol>
 *   <li>A payee at this bank: a transfer within the bank.</li>
 *   <li>J$1 million or more: JamClear-RTGS. The Bank of Jamaica requires electronic payments at or above the ACH
 *       value threshold to go by RTGS, and charges the sending institution for ACH payments that breach it.</li>
 *   <li>The customer chose RTGS (time-critical, smaller amounts): RTGS.</li>
 *   <li>Otherwise ACH.</li>
 * </ol>
 */
final class JmPaymentRouter implements PaymentRouter {

    private final PaymentScheme internal;
    private final PaymentScheme ach;
    private final PaymentScheme rtgs;
    private final BigDecimal achThreshold;

    JmPaymentRouter(List<PaymentScheme> schemes, BigDecimal achThreshold) {
        this.internal = find(schemes, JmMarketPack.INTERNAL);
        this.ach = find(schemes, JmMarketPack.ACH);
        this.rtgs = find(schemes, JmMarketPack.RTGS);
        this.achThreshold = achThreshold;
    }

    @Override
    public PaymentScheme route(PaymentIntent intent) {
        String bank = intent.creditor() == null ? null : intent.creditor().field(JmAccountScheme.BANK_CODE);
        if (intent.ownBank() != null && intent.ownBank().equals(bank)) {
            return internal;
        }
        if (intent.amount() != null && intent.amount().compareTo(achThreshold) >= 0) {
            return rtgs;
        }
        return JmMarketPack.RTGS.equals(intent.requestedScheme()) ? rtgs : ach;
    }

    private static PaymentScheme find(List<PaymentScheme> schemes, String code) {
        return schemes.stream().filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Jamaica pack has no " + code + " scheme"));
    }
}
