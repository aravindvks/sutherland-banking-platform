package bank.pbp.thredd.gateway;

/**
 * What the bank needs from Thredd, in our own terms. Connectors chosen by {@code vendor.thredd.mode}:
 * {@code simulator} (default) or {@code sandbox}. Tokens and last 4 only; never a PAN.
 */
public interface ThreddGateway {

    Card issueCard(String productId, String accountRef, String embossName, String idempotencyKey);

    /** ACTIVE, BLOCKED, LOST or CLOSED. */
    void setStatus(String token, String status, String idempotencyKey);

    /** Spending controls: daily limit (decimal string, market currency) and channel switches. */
    void setControls(String token, Controls controls, String idempotencyKey);

    record Controls(String dailyLimit, boolean online, boolean contactless, boolean atm) {
    }

    record Card(String token, String last4, String status) {
    }
}
