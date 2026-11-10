package bank.pbp.orchestrator.schedules;

import bank.pbp.orchestrator.payments.PaymentCommand;
import bank.pbp.orchestrator.payments.PaymentWorkflow;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Standing order or scheduled (future-dated) payment. Workflow ID {@code schedule-<scheduleId>}.
 *
 * <p>Sleeps until each due date (06:00 UK time), then runs an ordinary payment saga as a child
 * workflow, so every run gets the same checks as a payment made now: limits, Confirmation of
 * Payee, high-value review, CHAPS hours. A failed run (e.g. not enough money) is recorded and the
 * standing order carries on. Ends when cancelled, after the end date, or after a one-off payment.
 * History is kept short with continue-as-new every 24 runs.
 */
@WorkflowInterface
public interface ScheduleWorkflow {

    @WorkflowMethod
    String run(ScheduleRequest request, int startIndex);

    /** The customer cancelled the standing order or scheduled payment. */
    @SignalMethod
    void cancel(String requestedBy);

    /** Demo only: make the next payment now instead of on its due date. */
    @SignalMethod
    void runNow();

    /**
     * @param kind      ONE_OFF or STANDING_ORDER
     * @param frequency WEEKLY or MONTHLY (standing orders)
     * @param startDate first payment date (ISO); endDate optional, inclusive
     * @param scheme    FPS or CHAPS for other banks; null means FPS
     */
    record ScheduleRequest(String scheduleId, String customerId, String correlationId, String kind,
                           String frequency, String startDate, String endDate, String debtorAccount,
                           String creditorName, String creditorSortCode, String creditorAccountNumber,
                           String amount, String currency, String reference, String scheme, String copResult) {
    }

    @ActivityInterface
    interface ScheduleActivities {

        /** Inserts the schedule (ACTIVE) and publishes schedule.created. Idempotent. */
        void create(ScheduleRequest request);

        /** Builds the payment command for run number {@code index}: new payment and correlation IDs. */
        PaymentCommand prepareRun(ScheduleRequest request, int index);

        /** Records the run's outcome and the next due date (null when none is left). */
        void recordRun(ScheduleRequest request, int index, String paymentId, String paymentStatus, String nextDate);

        /** Ends the schedule: CANCELLED or COMPLETED, with an event. */
        void finish(ScheduleRequest request, String status, String reason);
    }

    class Impl implements ScheduleWorkflow {

        private static final int RUNS_PER_HISTORY = 24;

        private final ScheduleActivities activities = Workflow.newActivityStub(ScheduleActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(15))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setInitialInterval(Duration.ofSeconds(1))
                                .setBackoffCoefficient(2.0)
                                .setMaximumAttempts(10)
                                .build())
                        .build());

        private String cancelledBy;
        private boolean runNow;

        @Override
        public String run(ScheduleRequest request, int startIndex) {
            if (startIndex == 0) {
                activities.create(request);
            }
            for (int i = startIndex; ; i++) {
                LocalDate due = Schedules.dueDate(request, i);
                if (due == null) {
                    activities.finish(request, "COMPLETED", "No payments left");
                    return "COMPLETED";
                }
                long wait = Schedules.dueInstant(due).toEpochMilli() - Workflow.currentTimeMillis();
                if (wait > 0) {
                    Workflow.await(Duration.ofMillis(wait), () -> cancelledBy != null || runNow);
                }
                if (cancelledBy != null) {
                    activities.finish(request, "CANCELLED", "Cancelled by " + cancelledBy);
                    return "CANCELLED";
                }
                runNow = false;

                PaymentCommand command = activities.prepareRun(request, i);
                PaymentWorkflow payment = Workflow.newChildWorkflowStub(PaymentWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("payment-" + command.paymentId())
                                .setTaskQueue(TaskQueues.PAYMENTS)
                                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                                .build());
                String result;
                try {
                    result = payment.run(command);
                } catch (ChildWorkflowFailure failure) {
                    result = "FAILED";
                }
                LocalDate next = Schedules.dueDate(request, i + 1);
                activities.recordRun(request, i, command.paymentId(), result, next == null ? null : next.toString());

                if (i + 1 - startIndex >= RUNS_PER_HISTORY && next != null && cancelledBy == null) {
                    ScheduleWorkflow continued = Workflow.newContinueAsNewStub(ScheduleWorkflow.class);
                    continued.run(request, i + 1);
                    return "CONTINUED";
                }
            }
        }

        @Override
        public void cancel(String requestedBy) {
            if (cancelledBy == null) {
                cancelledBy = requestedBy == null ? "customer" : requestedBy;
            }
        }

        @Override
        public void runNow() {
            runNow = true;
        }
    }
}
