package bank.pbp.market.us;

import bank.pbp.common.market.PaymentIntent;
import bank.pbp.common.market.PaymentRouter;
import bank.pbp.common.market.PaymentScheme;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * US routing (docs/design/market-packs.md, 4.2):
 * <ol>
 *   <li>A payee with this bank's routing number: a transfer within the bank.</li>
 *   <li>The customer chose ACH or a wire: that scheme. The limit check then applies to it.</li>
 *   <li>Above the instant limit: a wire (Fedwire).</li>
 *   <li>Otherwise instant: RTP where the receiving bank is on RTP, else FedNow, else ACH.</li>
 * </ol>
 * Which banks each instant network reaches comes, in production, from the networks' directories through the core;
 * that lookup is not built. Until it is, the pack is told which routing numbers are not reachable
 * (pbp.market.us.rtp-unreachable, pbp.market.us.fednow-unreachable), which is how the simulators exercise the rule.
 */
final class UsPaymentRouter implements PaymentRouter {

    private final PaymentScheme internal;
    private final PaymentScheme rtp;
    private final PaymentScheme fedNow;
    private final PaymentScheme ach;
    private final PaymentScheme wire;
    private final Set<String> rtpUnreachable;
    private final Set<String> fedNowUnreachable;

    UsPaymentRouter(List<PaymentScheme> schemes, Set<String> rtpUnreachable, Set<String> fedNowUnreachable) {
        this.internal = find(schemes, UsMarketPack.INTERNAL);
        this.rtp = find(schemes, UsMarketPack.RTP);
        this.fedNow = find(schemes, UsMarketPack.FEDNOW);
        this.ach = find(schemes, UsMarketPack.ACH);
        this.wire = find(schemes, UsMarketPack.WIRE);
        this.rtpUnreachable = Set.copyOf(rtpUnreachable);
        this.fedNowUnreachable = Set.copyOf(fedNowUnreachable);
    }

    @Override
    public PaymentScheme route(PaymentIntent intent) {
        String routing = intent.creditor() == null ? null : intent.creditor().field(UsAbaScheme.ROUTING_NUMBER);
        if (intent.ownBank() != null && intent.ownBank().equals(routing)) {
            return internal;
        }
        if (UsMarketPack.ACH.equals(intent.requestedScheme())) {
            return ach;
        }
        if (UsMarketPack.WIRE.equals(intent.requestedScheme())) {
            return wire;
        }
        BigDecimal amount = intent.amount();
        if (amount != null && !rtp.allows(amount) && !fedNow.allows(amount)) {
            return wire;
        }
        if (routing != null && !rtpUnreachable.contains(routing) && (amount == null || rtp.allows(amount))) {
            return rtp;
        }
        if (routing != null && !fedNowUnreachable.contains(routing) && (amount == null || fedNow.allows(amount))) {
            return fedNow;
        }
        return ach;
    }

    private static PaymentScheme find(List<PaymentScheme> schemes, String code) {
        return schemes.stream().filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("US pack has no " + code + " scheme"));
    }
}
