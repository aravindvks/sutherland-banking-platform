package bank.pbp.credit.gateway;

import java.util.List;

/**
 * What the bank needs from the credit bureau and decision engine, in our own terms. Connectors chosen
 * by {@code vendor.credit.mode}: {@code simulator} (default) or {@code sandbox}. The engine holds the
 * lending policy; the orchestrator only acts on its outcome.
 */
public interface CreditDecisionGateway {

    Decision decide(Application application, String idempotencyKey);

    /** What the applicant told us. Money as decimal strings (market currency). */
    record Application(String applicationId, String customerId, String name, String product, String annualIncome,
                       String employmentStatus, String monthlyHousing, String monthlyCommitments,
                       String requestedLimit) {
    }

    /** outcome APPROVE, REFER or DECLINE; creditLimit and purchaseApr when not declined. */
    record Decision(String decisionId, String bureauRef, String searchType, String outcome, String creditLimit,
                    String purchaseApr, List<String> reasons) {
    }
}
