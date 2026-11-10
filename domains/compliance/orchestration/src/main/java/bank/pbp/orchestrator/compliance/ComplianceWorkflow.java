package bank.pbp.orchestrator.compliance;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;

/**
 * Compliance saga (HLD flow: sanctions hit). Atfinity's ongoing screening reports a possible
 * match; the customer is frozen (the cache updater pushes CUSTOMER_FROZEN to the EHI responder
 * within about 5 s) and every card is blocked at Thredd. The saga then waits for a staff review:
 * <ul>
 *   <li>{@code CLEAR} (false positive): customer ACTIVE, cards unblocked, FLAGGED inbound payments released</li>
 *   <li>{@code CONFIRM} (true match): customer CLOSED; cards stay blocked</li>
 * </ul>
 * Workflow ID {@code compliance-<saga ID>}; task queue "compliance".
 */
@WorkflowInterface
public interface ComplianceWorkflow {

    Duration REVIEW_WAIT = Duration.ofDays(30);

    @WorkflowMethod
    String freeze(String sagaId, String customerId, String correlationId, String reason);

    /** From the staff portal: CLEAR or CONFIRM, with the reviewer and a note. */
    @SignalMethod
    void reviewDecision(String decision, String reviewer, String note);

    @QueryMethod
    String status();

    @ActivityInterface
    interface ComplianceActivities {

        void startSaga(String sagaId, String customerId, String correlationId);

        /** ACTIVE → FROZEN. Returns false if the customer was not ACTIVE. */
        boolean freezeCustomer(String customerId, String correlationId, String reason);

        /** Sets every card of the customer to the status at Thredd. Returns how many. */
        int setCardStatus(String customerId, String correlationId, String status);

        /** FROZEN → ACTIVE or CLOSED, recorded with the reviewer as actor. */
        void decideCustomer(String customerId, String correlationId, String status, String reviewer, String note);

        /** FLAGGED inbound payments → COMPLETED. Returns how many. */
        int releaseFlaggedPayments(String customerId, String correlationId, String reviewer);

        void finishSaga(String sagaId, String step, String status);
    }

    class Impl implements ComplianceWorkflow {

        private final ComplianceActivities activities = Workflow.newActivityStub(ComplianceActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(20))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setInitialInterval(Duration.ofSeconds(1))
                                .setBackoffCoefficient(2.0)
                                .setMaximumAttempts(10)
                                .build())
                        .build());

        private String state = "STARTED";
        private String decision;
        private String reviewer;
        private String note;

        @Override
        public String freeze(String sagaId, String customerId, String correlationId, String reason) {
            activities.startSaga(sagaId, customerId, correlationId);
            boolean frozen = activities.freezeCustomer(customerId, correlationId, reason);
            if (!frozen) {
                state = "NOT_ACTIVE";
                activities.finishSaga(sagaId, "CUSTOMER_NOT_ACTIVE", "COMPLETED");
                return state;
            }
            activities.setCardStatus(customerId, correlationId, "BLOCKED");
            state = "WAITING_FOR_REVIEW";
            activities.finishSaga(sagaId, state, "WAITING");

            boolean decided = Workflow.await(REVIEW_WAIT, () -> decision != null);
            if (!decided) {
                state = "REVIEW_OVERDUE";
                activities.finishSaga(sagaId, state, "FAILED");
                return state;
            }
            if ("CLEAR".equals(decision)) {
                activities.decideCustomer(customerId, correlationId, "ACTIVE", reviewer, note);
                activities.setCardStatus(customerId, correlationId, "ACTIVE");
                activities.releaseFlaggedPayments(customerId, correlationId, reviewer);
                state = "CLEARED";
            } else {
                activities.decideCustomer(customerId, correlationId, "CLOSED", reviewer, note);
                state = "CONFIRMED_CLOSED";
            }
            activities.finishSaga(sagaId, state, "COMPLETED");
            return state;
        }

        @Override
        public void reviewDecision(String d, String r, String n) {
            if ("CLEAR".equals(d) || "CONFIRM".equals(d)) {
                this.decision = d;
                this.reviewer = r;
                this.note = n;
            }
        }

        @Override
        public String status() {
            return state;
        }
    }
}
