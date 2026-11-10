package bank.pbp.orchestrator.temporal;

/** Temporal task queues (HLD decision on Temporal; LLD section 3). */
public final class TaskQueues {

    public static final String ONBOARDING = "onboarding";
    public static final String CARDS = "cards";
    public static final String PAYMENTS = "payments";
    public static final String COMPLIANCE = "compliance";

    private TaskQueues() {
    }
}
