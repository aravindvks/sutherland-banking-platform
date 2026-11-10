package bank.pbp.orchestrator.creditcards;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;

/**
 * Credit cards on Thredd credit processing (LLD section 3, "Credit cards"). Thredd holds the credit
 * account; a decision engine behind the credit adapter holds the lending policy; the orchestrator
 * runs the journeys. Four sagas on the cards queue:
 * <ul>
 *   <li>{@link ApplicationWorkflow} {@code ccapp-<applicationId>}: soft search and decision; a
 *       REFER waits for an underwriter; an offer (with the pre-contract information) waits for the
 *       customer to accept; then the Thredd credit account and the credit card are opened.</li>
 *   <li>{@link RepaymentWorkflow} {@code ccrepay-<repaymentId>}: money from the customer's current
 *       account (Essence) to the bank's card repayment account, then posted to Thredd. If Thredd
 *       refuses it for good, the money goes back (REFUNDED).</li>
 *   <li>{@link StatementWorkflow} {@code ccstmt-<statementId>}: Thredd cut a statement; at the due
 *       time the bank asks Thredd whether the minimum was paid. Missed: late fee, account in ARREARS.</li>
 *   <li>{@link WithdrawalWorkflow} {@code ccwithdraw-<accountId>}: the 14-day right to withdraw.</li>
 * </ul>
 */
public final class CreditCardWorkflows {

    private CreditCardWorkflows() {
    }

    static ActivityOptions options() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(20))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setBackoffCoefficient(2.0)
                        .setMaximumAttempts(10)
                        .build())
                .build();
    }

    // ---------------------------------------------------------------------------------- types

    public record Application(String applicationId, String customerId, String correlationId, String name,
                              String annualIncome, String employmentStatus, String monthlyHousing,
                              String monthlyCommitments, String requestedLimit) {
    }

    /** From the decision engine: APPROVE, REFER or DECLINE, with the limit and APR it would offer. */
    public record Decision(String outcome, String creditLimit, String purchaseApr, List<String> reasons) {
    }

    /** An underwriter's decision on a referral: APPROVE (with a limit) or DECLINE. */
    public record Underwriting(String decision, String creditLimit, String reviewer, String note) {
    }

    public record Opened(String accountId, String cardToken, String last4) {
    }

    public record Repayment(String repaymentId, String customerId, String correlationId, String accountId,
                            String debitAccount, String amount) {
    }

    public record StatementNotice(String statementId, String accountId, String correlationId, String statementDate,
                                  String balance, String minimumPayment, String interest, String dueDate,
                                  String dueAt) {
    }

    /** PAID_IN_FULL, MINIMUM_PAID, DUE or MISSED, as Thredd sees it now. */
    public record StatementStatus(String status, String repaid) {
    }

    public record Withdrawal(String accountId, String customerId, String correlationId) {
    }

    // ---------------------------------------------------------------------------------- activities

    /** Activity names start with "credit" so they stay unique on the cards worker. */
    @ActivityInterface
    public interface CreditCardActivities {

        /** Records the application (RECEIVED); NOT_ELIGIBLE when the customer is not ACTIVE or already has one. */
        String creditRecordApplication(Application a);

        /** Soft search and decision through the credit adapter; stored with its reasons. */
        Decision creditDecide(Application a);

        void creditDeclined(Application a, String outcome, List<String> reasons, String reviewer);

        void creditReferred(Application a, Decision d);

        /** OFFERED, with the pre-contract summary in the event (limit, APR, minimum payment rule). */
        void creditOffered(Application a, String creditLimit, String purchaseApr, String reviewer);

        void creditExpired(Application a, String stage);

        /** Thredd credit account, credit card, mapping links, credit_account row; OPEN. Idempotent. */
        Opened creditOpenAccount(Application a, String creditLimit, String purchaseApr);

        /** Checks the card account and the debit account are the customer's; INITIATED. */
        String creditRecordRepayment(Repayment r);

        /** Essence: current account → card repayment account. Non-retryable PAY-FUNDS-001 without the money. */
        String creditDebitCurrentAccount(Repayment r);

        String creditPostRepayment(Repayment r);

        /** Money back to the current account when Thredd will not take the repayment. */
        void creditRefundRepayment(Repayment r);

        void creditRepaymentStatus(Repayment r, String status, String reason);

        void creditRecordStatement(StatementNotice s);

        StatementStatus creditStatementStatus(StatementNotice s);

        void creditStatementPaid(StatementNotice s, StatementStatus status);

        /** Late fee from the bank's tariff, posted by Thredd. Returns the fee. */
        String creditChargeLateFee(StatementNotice s);

        void creditStatementMissed(StatementNotice s, String fee);

        /** Within 14 days of opening and with nothing owed: Thredd account closed, WITHDRAWN. */
        String creditWithdraw(Withdrawal w);
    }

    // ---------------------------------------------------------------------------------- application

    @WorkflowInterface
    public interface ApplicationWorkflow {

        @WorkflowMethod
        String run(Application a, String referralTimeout, String offerValidity);

        @SignalMethod
        void underwriting(Underwriting u);

        @SignalMethod
        void accept(String agreementVersion);
    }

    public static class ApplicationImpl implements ApplicationWorkflow {

        private final CreditCardActivities activities = Workflow.newActivityStub(CreditCardActivities.class, options());
        private Underwriting underwriting;
        private String accepted;

        @Override
        public String run(Application a, String referralTimeout, String offerValidity) {
            String eligible = activities.creditRecordApplication(a);
            if (!"ELIGIBLE".equals(eligible)) {
                activities.creditDeclined(a, "DECLINE", List.of(eligible), "svc:orchestrator");
                return "DECLINED";
            }
            Decision d = activities.creditDecide(a);
            String limit = d.creditLimit();
            String reviewer = "engine";
            switch (d.outcome()) {
                case "APPROVE" -> {
                }
                case "REFER" -> {
                    activities.creditReferred(a, d);
                    boolean decided = Workflow.await(Duration.parse(referralTimeout), () -> underwriting != null);
                    if (!decided) {
                        activities.creditExpired(a, "REFERRAL");
                        return "EXPIRED";
                    }
                    if (!"APPROVE".equals(underwriting.decision())) {
                        activities.creditDeclined(a, "DECLINE", List.of("UNDERWRITER_DECLINED"), underwriting.reviewer());
                        return "DECLINED";
                    }
                    limit = underwriting.creditLimit() != null ? underwriting.creditLimit() : d.creditLimit();
                    reviewer = underwriting.reviewer();
                }
                default -> {
                    activities.creditDeclined(a, "DECLINE", d.reasons(), "engine");
                    return "DECLINED";
                }
            }
            activities.creditOffered(a, limit, d.purchaseApr(), reviewer);
            boolean taken = Workflow.await(Duration.parse(offerValidity), () -> accepted != null);
            if (!taken) {
                activities.creditExpired(a, "OFFER");
                return "EXPIRED";
            }
            activities.creditOpenAccount(a, limit, d.purchaseApr());
            return "OPEN";
        }

        @Override
        public void underwriting(Underwriting u) {
            if (underwriting == null) {
                underwriting = u;
            }
        }

        @Override
        public void accept(String agreementVersion) {
            if (accepted == null) {
                accepted = agreementVersion == null ? "v1" : agreementVersion;
            }
        }
    }

    // ---------------------------------------------------------------------------------- repayment

    @WorkflowInterface
    public interface RepaymentWorkflow {

        @WorkflowMethod
        String run(Repayment r);
    }

    public static class RepaymentImpl implements RepaymentWorkflow {

        private final CreditCardActivities activities = Workflow.newActivityStub(CreditCardActivities.class, options());
        /** Posting to Thredd: a short retry window, then the money goes back to the customer. */
        private final CreditCardActivities posting = Workflow.newActivityStub(CreditCardActivities.class,
                ActivityOptions.newBuilder(options())
                        .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(1))
                                .setBackoffCoefficient(2.0).setMaximumAttempts(6).build())
                        .build());

        @Override
        public String run(Repayment r) {
            String checked = activities.creditRecordRepayment(r);
            if (!"OK".equals(checked)) {
                activities.creditRepaymentStatus(r, "FAILED", checked);
                return "FAILED";
            }
            try {
                activities.creditDebitCurrentAccount(r);
            } catch (ActivityFailure e) {
                activities.creditRepaymentStatus(r, "FAILED", code(e));
                return "FAILED";
            }
            activities.creditRepaymentStatus(r, "DEBITED", null);
            try {
                posting.creditPostRepayment(r);
            } catch (ActivityFailure e) {
                activities.creditRefundRepayment(r);
                activities.creditRepaymentStatus(r, "REFUNDED", code(e));
                return "REFUNDED";
            }
            activities.creditRepaymentStatus(r, "COMPLETED", null);
            return "COMPLETED";
        }
    }

    // ---------------------------------------------------------------------------------- statement

    @WorkflowInterface
    public interface StatementWorkflow {

        @WorkflowMethod
        String run(StatementNotice s);
    }

    public static class StatementImpl implements StatementWorkflow {

        private final CreditCardActivities activities = Workflow.newActivityStub(CreditCardActivities.class, options());

        @Override
        public String run(StatementNotice s) {
            activities.creditRecordStatement(s);
            long due = java.time.Instant.parse(s.dueAt()).toEpochMilli() - Workflow.currentTimeMillis();
            if (due > 0) {
                Workflow.sleep(Duration.ofMillis(due));
            }
            StatementStatus status = activities.creditStatementStatus(s);
            if ("PAID_IN_FULL".equals(status.status()) || "MINIMUM_PAID".equals(status.status())) {
                activities.creditStatementPaid(s, status);
                return status.status();
            }
            String fee = activities.creditChargeLateFee(s);
            activities.creditStatementMissed(s, fee);
            return "MISSED";
        }
    }

    // ---------------------------------------------------------------------------------- withdrawal

    @WorkflowInterface
    public interface WithdrawalWorkflow {

        @WorkflowMethod
        String run(Withdrawal w);
    }

    public static class WithdrawalImpl implements WithdrawalWorkflow {

        private final CreditCardActivities activities = Workflow.newActivityStub(CreditCardActivities.class, options());

        @Override
        public String run(Withdrawal w) {
            return activities.creditWithdraw(w);
        }
    }

    static String code(ActivityFailure e) {
        return e.getCause() instanceof ApplicationFailure af && af.getType() != null ? af.getType() : "ERROR";
    }
}
