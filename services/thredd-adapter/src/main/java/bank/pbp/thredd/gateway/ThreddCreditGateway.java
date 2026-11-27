package bank.pbp.thredd.gateway;

import java.util.Map;

/**
 * Thredd credit processing, in our own terms: the credit card account (limit, balance, open-to-buy,
 * interest, minimum payment, statements) is held by Thredd. Money as decimal strings in the market currency.
 * Connectors chosen by {@code vendor.thredd.mode}, like {@link ThreddGateway}.
 */
public interface ThreddCreditGateway {

    CreditAccount open(String customerRef, String creditLimit, String purchaseApr, String productId,
                       String idempotencyKey);

    CreditAccount get(String accountId);

    CreditAccount setLimit(String accountId, String creditLimit, String idempotencyKey);

    CreditAccount close(String accountId, String idempotencyKey);

    /** Authorisation against open-to-buy. Over the limit → PbpException CARD-CREDIT-001. */
    String hold(String accountId, String amount, String reference, String idempotencyKey);

    String capture(String holdId, String amount);

    void release(String holdId);

    String repay(String accountId, String amount, String reference, String idempotencyKey);

    String chargeFee(String accountId, String amount, String type, String idempotencyKey);

    Statement statement(String statementId);

    record CreditAccount(String accountId, String status, String creditLimit, String purchaseApr, String balance,
                         String pending, String availableCredit, Statement lastStatement, Object transactions) {
    }

    /** status PAID_IN_FULL, MINIMUM_PAID, DUE or MISSED, as Thredd sees it now. */
    record Statement(String statementId, String accountId, String statementDate, String balance,
                     String minimumPayment, String interest, String fees, String dueDate, String dueAt,
                     String repaid, String status) {

        @SuppressWarnings("unchecked")
        static Statement from(Object o) {
            if (!(o instanceof Map<?, ?> raw)) {
                return null;
            }
            Map<String, Object> m = (Map<String, Object>) raw;
            return new Statement(s(m, "statementId"), s(m, "accountId"), s(m, "statementDate"), s(m, "balance"),
                    s(m, "minimumPayment"), s(m, "interest"), s(m, "fees"), s(m, "dueDate"), s(m, "dueAt"),
                    s(m, "repaid"), s(m, "status"));
        }

        private static String s(Map<String, Object> m, String k) {
            return m.get(k) == null ? null : String.valueOf(m.get(k));
        }
    }
}
