package bank.pbp.orchestrator.onboarding;

import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingCommand;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingStatus;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Onboarding saga. Workflow ID {@code onboarding-<onboardingId>}; task queue "onboarding". */
@WorkflowInterface
public interface OnboardingWorkflow {

    @WorkflowMethod
    OnboardingStatus run(OnboardingCommand command);

    /** From kyc-events: approved, referred or rejected. */
    @SignalMethod
    void kycOutcome(String outcome, String riskRating);

    @QueryMethod
    OnboardingStatus status();
}
