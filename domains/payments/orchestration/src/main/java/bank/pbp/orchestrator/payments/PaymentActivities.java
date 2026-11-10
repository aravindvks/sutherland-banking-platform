package bank.pbp.orchestrator.payments;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface PaymentActivities {

    /** Inserts the payment (INITIATED) and its saga row. Idempotent. */
    void createPayment(PaymentCommand command);

    /**
     * Limits and policy: single-payment limit per scheme, whether staff must approve the payment
     * (high value), and the tariff fee (CHAPS).
     */
    RiskResult riskCheck(PaymentCommand command);

    /**
     * Confirmation of Payee through the Essence adapter, recorded on the payment. The saga
     * decides what the result means; a failure here is treated as UNAVAILABLE.
     */
    CopCheck confirmPayee(PaymentCommand command);

    /** Whether CHAPS is open now and, if not, how long until it opens (UK business hours). */
    Window chapsWindow();

    /**
     * Moves the payment from one status to the next, writes history and publishes the payment
     * event. Idempotent: does nothing when the payment is no longer in {@code from}.
     */
    void transition(PaymentCommand command, String from, String to, String reason);

    /**
     * Same-bank transfer through the Essence adapter. Returns the Essence posting reference.
     * Throws a non-retryable failure typed with the LLD error code when Essence refuses it.
     */
    String postInternalTransfer(PaymentCommand command);

    /**
     * Outbound UK payment through the Essence adapter on the scheme in {@code paymentType}
     * (FPS or CHAPS). Records and returns Finastra's reference. Non-retryable failure when refused.
     */
    String submitPayment(PaymentCommand command);

    /** Posts the tariff fee in Essence (reference = payment ID) and records it on the payment. */
    String chargeFee(PaymentCommand command, String fee);

    /**
     * @param reviewRequired the amount is at or above the high-value threshold: staff must approve
     * @param fee            tariff fee as a decimal string, "0.00" when free
     */
    record RiskResult(boolean passed, String code, String reason, boolean reviewRequired, String fee) {

        public static RiskResult pass(boolean reviewRequired, String fee) {
            return new RiskResult(true, null, null, reviewRequired, fee);
        }

        public static RiskResult fail(String code, String reason) {
            return new RiskResult(false, code, reason, false, "0.00");
        }
    }

    /** result: MATCH, CLOSE_MATCH, NO_MATCH or UNAVAILABLE; matchedName is the real name on a close match. */
    record CopCheck(String result, String matchedName, String reasonCode) {
    }

    /** opensAt is ISO 8601; waitMillis is 0 when open. */
    record Window(boolean open, String opensAt, long waitMillis) {
    }
}
