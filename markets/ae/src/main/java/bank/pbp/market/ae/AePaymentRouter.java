package bank.pbp.market.ae;

import bank.pbp.common.market.PaymentIntent;
import bank.pbp.common.market.PaymentRouter;
import bank.pbp.common.market.PaymentScheme;
import java.util.List;
import java.util.Set;

/**
 * UAE routing (docs/design/market-packs.md, 4.2):
 * <ol>
 *   <li>A payee at this bank (the IBAN's bank code): a transfer within the bank.</li>
 *   <li>The customer chose UAEFTS: UAEFTS.</li>
 *   <li>Within the bank's Aani limit and the payee's bank is on Aani: Aani.</li>
 *   <li>Otherwise UAEFTS.</li>
 * </ol>
 * Which banks are on Aani comes, in production, from Al Etihad Payments' participant list through the core; until
 * that lookup is built the pack is told which bank codes are not (pbp.market.ae.aani-unreachable).
 */
final class AePaymentRouter implements PaymentRouter {

    private final PaymentScheme internal;
    private final PaymentScheme aani;
    private final PaymentScheme fts;
    private final Set<String> aaniUnreachable;

    AePaymentRouter(List<PaymentScheme> schemes, Set<String> aaniUnreachable) {
        this.internal = find(schemes, AeMarketPack.INTERNAL);
        this.aani = find(schemes, AeMarketPack.AANI);
        this.fts = find(schemes, AeMarketPack.UAEFTS);
        this.aaniUnreachable = Set.copyOf(aaniUnreachable);
    }

    @Override
    public PaymentScheme route(PaymentIntent intent) {
        String bank = intent.creditor() == null ? null : AeIbanScheme.bankCode(intent.creditor().field(AeIbanScheme.IBAN));
        if (intent.ownBank() != null && intent.ownBank().equals(bank)) {
            return internal;
        }
        if (AeMarketPack.UAEFTS.equals(intent.requestedScheme())) {
            return fts;
        }
        boolean fits = intent.amount() == null || aani.allows(intent.amount());
        return bank != null && fits && !aaniUnreachable.contains(bank) ? aani : fts;
    }

    private static PaymentScheme find(List<PaymentScheme> schemes, String code) {
        return schemes.stream().filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("UAE pack has no " + code + " scheme"));
    }
}
