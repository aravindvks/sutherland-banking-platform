package bank.pbp.market.co;

import bank.pbp.common.market.PaymentIntent;
import bank.pbp.common.market.PaymentRouter;
import bank.pbp.common.market.PaymentScheme;
import java.util.List;
import java.util.Set;

/**
 * Colombian routing (docs/design/market-packs.md, 4.2):
 * <ol>
 *   <li>A payee at this bank: a transfer within the bank.</li>
 *   <li>The customer chose ACH or a CUD transfer: that scheme.</li>
 *   <li>Within the bank's Bre-B limit and the payee's institution is on Bre-B: Bre-B.</li>
 *   <li>Otherwise ACH. Larger transfers go by ACH unless the customer asks for CUD.</li>
 * </ol>
 * Which institutions are on Bre-B comes, in production, from the Banco de la República's directory through the core;
 * until that lookup is built the pack is told which bank codes are not (pbp.market.co.breb-unreachable).
 */
final class CoPaymentRouter implements PaymentRouter {

    private final PaymentScheme internal;
    private final PaymentScheme breB;
    private final PaymentScheme ach;
    private final PaymentScheme cud;
    private final Set<String> breBUnreachable;

    CoPaymentRouter(List<PaymentScheme> schemes, Set<String> breBUnreachable) {
        this.internal = find(schemes, CoMarketPack.INTERNAL);
        this.breB = find(schemes, CoMarketPack.BREB);
        this.ach = find(schemes, CoMarketPack.ACH);
        this.cud = find(schemes, CoMarketPack.CUD);
        this.breBUnreachable = Set.copyOf(breBUnreachable);
    }

    @Override
    public PaymentScheme route(PaymentIntent intent) {
        String bank = intent.creditor() == null ? null : intent.creditor().field(CoAccountScheme.BANK_CODE);
        if (intent.ownBank() != null && intent.ownBank().equals(bank)) {
            return internal;
        }
        if (CoMarketPack.ACH.equals(intent.requestedScheme())) {
            return ach;
        }
        if (CoMarketPack.CUD.equals(intent.requestedScheme())) {
            return cud;
        }
        boolean fits = intent.amount() == null || breB.allows(intent.amount());
        return bank != null && fits && !breBUnreachable.contains(bank) ? breB : ach;
    }

    private static PaymentScheme find(List<PaymentScheme> schemes, String code) {
        return schemes.stream().filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Colombia pack has no " + code + " scheme"));
    }
}
