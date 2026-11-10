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
        String scheduleId) {
}
