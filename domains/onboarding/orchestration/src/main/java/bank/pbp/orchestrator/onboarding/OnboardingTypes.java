package bank.pbp.orchestrator.onboarding;

/** Data carried by the onboarding saga. Plain strings keep it safe for Temporal's JSON converter. */
public final class OnboardingTypes {

    private OnboardingTypes() {
    }

    /**
     * {@code issueDebitCard}: whether this bank runs the debit cards module (set when the saga starts, so a running
     * saga never changes its mind). Null on commands from before the switch existed, which means issue a card.
     */
    public record OnboardingCommand(String onboardingId, String correlationId, String customerType, String name,
                                    String email, Boolean issueDebitCard) {

        public boolean wantsDebitCard() {
            return !Boolean.FALSE.equals(issueDebitCard);
        }
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
