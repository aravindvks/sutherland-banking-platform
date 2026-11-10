package bank.pbp.orchestrator.onboarding;

/** Data carried by the onboarding saga. Plain strings keep it safe for Temporal's JSON converter. */
public final class OnboardingTypes {

    private OnboardingTypes() {
    }

    public record OnboardingCommand(String onboardingId, String correlationId, String customerType, String name,
                                    String email) {
    }

    /** What the customer's app shows while onboarding runs and after it ends. */
    public record OnboardingStatus(String onboardingId, String status, String step, String customerId,
                                   String accountId, String sortCode, String accountNumber, String cardLast4,
                                   String reason) {
    }

    public record AccountDetails(String accountId, String sortCode, String accountNumber, String accountRefId) {
    }

    public record CardDetails(String token, String last4) {
    }
}
