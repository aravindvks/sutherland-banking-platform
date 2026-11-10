package bank.pbp.orchestrator.onboarding;

import bank.pbp.orchestrator.onboarding.OnboardingTypes.AccountDetails;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.CardDetails;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingCommand;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingStatus;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * Onboarding saga (HLD flow A): customer → KYC case → wait for Atfinity → Essence party and
 * account → Thredd card → customer ACTIVE. A referred case keeps waiting for a final decision;
 * a rejected case ends with the customer REJECTED. Compensation for failures after KYC approval
 * (closing a half-opened account) arrives with the staff portal in Phase 2.
 */
public class OnboardingWorkflowImpl implements OnboardingWorkflow {

    static final Duration KYC_WAIT = Duration.ofDays(7);

    private final OnboardingActivities activities = Workflow.newActivityStub(OnboardingActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(20))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(8)
                            .build())
                    .build());

    private OnboardingCommand command;
    private String status = "STARTED";
    private String step = "START";
    private String customerId;
    private AccountDetails account;
    private String cardLast4;
    private String reason;
    private String kycOutcome;
    private String riskRating;

    @Override
    public OnboardingStatus run(OnboardingCommand cmd) {
        this.command = cmd;
        customerId = activities.createCustomer(cmd);
        step = "CUSTOMER_CREATED";
        activities.openKycCase(cmd, customerId);
        moveTo("WAITING", "WAITING_FOR_KYC");

        while (true) {
            boolean decided = Workflow.await(KYC_WAIT, () -> kycOutcome != null);
            if (!decided) {
                reason = "No KYC decision within " + KYC_WAIT.toDays() + " days";
                return end("FAILED");
            }
            if (!"referred".equals(kycOutcome)) {
                break;
            }
            moveTo("WAITING", "KYC_REFERRED");
            kycOutcome = null;
        }

        if ("rejected".equals(kycOutcome)) {
            activities.setCustomerStatus(cmd, customerId, "REJECTED", "KYC rejected by Atfinity");
            reason = "KYC rejected";
            step = "KYC_REJECTED";
            return end("REJECTED");
        }

        try {
            activities.applyKyc(cmd, customerId, riskRating);
            moveTo("RUNNING", "KYC_APPROVED");
            account = activities.openAccount(cmd, customerId);
            moveTo("RUNNING", "ACCOUNT_OPENED");
            CardDetails card = activities.issueCard(cmd, customerId, account);
            cardLast4 = card.last4();
            moveTo("RUNNING", "CARD_ISSUED");
            activities.setCustomerStatus(cmd, customerId, "ACTIVE", "Onboarding completed");
            step = "ACTIVE";
            return end("COMPLETED");
        } catch (ActivityFailure failure) {
            reason = failure.getCause() instanceof ApplicationFailure app
                    ? app.getType() + ": " + app.getOriginalMessage()
                    : "A vendor did not respond after retries";
            return end("FAILED");
        }
    }

    @Override
    public void kycOutcome(String outcome, String rating) {
        this.kycOutcome = outcome;
        this.riskRating = rating;
    }

    @Override
    public OnboardingStatus status() {
        return snapshot();
    }

    private void moveTo(String newStatus, String newStep) {
        status = newStatus;
        step = newStep;
        activities.recordStep(command, newStep, newStatus);
    }

    private OnboardingStatus end(String finalStatus) {
        status = finalStatus;
        OnboardingStatus s = snapshot();
        activities.finish(command, s);
        return s;
    }

    private OnboardingStatus snapshot() {
        return new OnboardingStatus(command == null ? null : command.onboardingId(), status, step, customerId,
                account == null ? null : account.accountId(),
                account == null ? null : account.sortCode(),
                account == null ? null : account.accountNumber(),
                cardLast4, reason);
    }
}
