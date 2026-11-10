package bank.pbp.orchestrator.onboarding;

import bank.pbp.orchestrator.onboarding.OnboardingTypes.AccountDetails;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.CardDetails;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingCommand;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingStatus;
import io.temporal.activity.ActivityInterface;

/** Every activity is idempotent, so Temporal can retry any of them safely. */
@ActivityInterface
public interface OnboardingActivities {

    /** Creates the master customer (status ONBOARDING) and the saga row. Returns the customer ID. */
    String createCustomer(OnboardingCommand command);

    /** Opens the Atfinity case and links it to the customer. Returns the case ID. */
    String openKycCase(OnboardingCommand command, String customerId);

    void recordStep(OnboardingCommand command, String step, String sagaStatus);

    void applyKyc(OnboardingCommand command, String customerId, String riskRating);

    /** Essence party and current account, both linked in the mapping service. */
    AccountDetails openAccount(OnboardingCommand command, String customerId);

    /** Thredd debit card for the account, linked in the mapping service. */
    CardDetails issueCard(OnboardingCommand command, String customerId, AccountDetails account);

    /** Moves the customer to ACTIVE or REJECTED; does nothing if already there. */
    void setCustomerStatus(OnboardingCommand command, String customerId, String status, String reason);

    /** Closes the saga row and publishes onboarding.completed, onboarding.rejected or onboarding.failed. */
    void finish(OnboardingCommand command, OnboardingStatus status);
}
