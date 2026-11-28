package bank.pbp.common.market;

/**
 * Chooses the scheme for a payment (docs/design/market-packs.md, section 4.2): same-bank first, then the
 * market's rules, then the customer's choice where the market allows one. The orchestrator calls it when a
 * payment command arrives and when a standing order prepares a run; it is never called from workflow code.
 *
 * <p>The router only chooses. Whether the payment is within the scheme's limit, and whether the scheme is open
 * now, are checked later by the payment workflow, as before.
 */
@FunctionalInterface
public interface PaymentRouter {

    PaymentScheme route(PaymentIntent intent);
}
