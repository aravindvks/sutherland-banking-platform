package bank.pbp.market.uk;

import bank.pbp.common.market.PaymentIntent;
import bank.pbp.common.market.PaymentRouter;
import bank.pbp.common.market.PaymentScheme;
import java.util.List;

/**
 * UK routing, as the orchestrator chose before market packs: a payee with this bank's sort code is a transfer
 * within the bank; otherwise CHAPS when the customer chose it, else Faster Payments. The amount does not change
 * the choice: a Faster Payment above its limit is rejected by the limit check, not moved to CHAPS.
 */
final class UkPaymentRouter implements PaymentRouter {

    private final PaymentScheme internal;
    private final PaymentScheme fps;
    private final PaymentScheme chaps;

    UkPaymentRouter(List<PaymentScheme> schemes) {
        this.internal = find(schemes, "INTERNAL");
        this.fps = find(schemes, "FPS");
        this.chaps = find(schemes, "CHAPS");
    }

    @Override
    public PaymentScheme route(PaymentIntent intent) {
        Object sortCode = intent.creditor() == null ? null : intent.creditor().fields().get("sortCode");
        if (intent.ownBank() != null && intent.ownBank().equals(sortCode)) {
            return internal;
        }
        return "CHAPS".equals(intent.requestedScheme()) ? chaps : fps;
    }

    private static PaymentScheme find(List<PaymentScheme> schemes, String code) {
        return schemes.stream().filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalStateException("UK pack has no " + code + " scheme"));
    }
}
