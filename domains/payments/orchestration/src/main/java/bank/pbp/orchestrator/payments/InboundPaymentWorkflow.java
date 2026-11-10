package bank.pbp.orchestrator.payments;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;

/**
 * Inbound payment (HLD flow: money arriving from another bank). Finastra has already credited
 * the account; the bank records it, links it to the customer and decides how to treat it:
 * RECEIVED → COMPLETED, or FLAGGED for staff review when the customer is not ACTIVE.
 * Workflow ID {@code inbound-<finastraRef>}, so a repeated notification is recorded once.
 */
@WorkflowInterface
public interface InboundPaymentWorkflow {

    @WorkflowMethod
    String receive(InboundCredit credit);

    record InboundCredit(String paymentId, String finastraRef, String correlationId, String creditAccount,
                         String amount, String currency, String debtorName, String debtorSortCode,
                         String reference, String scheme, String receivedAt) {
    }

    record Owner(String customerId, String customerStatus) {
    }

    @ActivityInterface
    interface InboundActivities {

        /** Customer that owns the credited account (via the mapping service). */
        Owner owner(InboundCredit credit);

        /** Inserts the INBOUND payment row (status RECEIVED). Idempotent on the Finastra reference. */
        void record(InboundCredit credit, String customerId);

        /** RECEIVED → COMPLETED or FLAGGED, with history and a payment event. */
        void settle(InboundCredit credit, String customerId, String status, String reason);
    }

    class Impl implements InboundPaymentWorkflow {

        private final InboundActivities activities = Workflow.newActivityStub(InboundActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(15))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setInitialInterval(Duration.ofSeconds(1))
                                .setBackoffCoefficient(2.0)
                                .setMaximumAttempts(10)
                                .build())
                        .build());

        @Override
        public String receive(InboundCredit credit) {
            Owner owner = activities.owner(credit);
            activities.record(credit, owner.customerId());
            if (!"ACTIVE".equals(owner.customerStatus())) {
                activities.settle(credit, owner.customerId(), "FLAGGED",
                        "Customer is " + owner.customerStatus() + "; held for staff review");
                return "FLAGGED";
            }
            activities.settle(credit, owner.customerId(), "COMPLETED", null);
            return "COMPLETED";
        }
    }
}
