package bank.pbp.orchestrator.payments;

/**
 * Workflow input built from a payment.create command, or by a standing order when a payment falls
 * due. Plain strings keep it safe for Temporal's JSON data converter; amounts stay decimal strings.
 *
 * @param paymentType INTERNAL when the creditor sort code is this bank's; otherwise CHAPS when the
 *                    customer chose CHAPS, else FPS
 * @param initiatedAt ISO 8601 instant; also the payment row's partition key
 * @param copResult   the Confirmation of Payee result the customer saw and accepted (CLOSE_MATCH
 *                    means they confirmed the payee anyway); the saga checks again itself
 * @param scheduleId  the standing order or scheduled payment that made this payment, or null
 * @param creditorAccount the creditor's account as the market's identifier (Stage 1 step 4); null in commands
 *                    from before identifiers, which {@link #account()} rebuilds from the sort code and number
 */
public record PaymentCommand(
        String paymentId,
        String customerId,
        String correlationId,
        String debtorAccount,
        String creditorName,
        String creditorSortCode,
        String creditorAccountNumber,
        String amount,
        String currency,
        String remittanceInfo,
        String paymentType,
        String idempotencyKey,
        String initiatedAt,
        String copResult,
        String scheduleId,
        bank.pbp.common.market.AccountIdentifier creditorAccount) {

    /** The creditor's account, from {@code creditorAccount} or, in older commands, the original fields. */
    public bank.pbp.common.market.AccountIdentifier account() {
        return creditorAccount != null ? creditorAccount
                : bank.pbp.common.market.AccountIdentifier.fromOriginalShape(creditorSortCode, creditorAccountNumber);
    }
}
