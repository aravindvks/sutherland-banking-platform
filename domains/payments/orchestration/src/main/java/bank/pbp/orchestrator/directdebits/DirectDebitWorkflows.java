package bank.pbp.orchestrator.directdebits;

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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Direct Debits, with this bank as the payer's bank (Bacs). Three sagas on the payments queue:
 * <ul>
 *   <li>{@link MandateWorkflow} {@code ddmandate-<mandateRef>}: an originator lodged a Direct
 *       Debit Instruction (AUDDIS). Recorded ACTIVE; waits for the customer to cancel it.</li>
 *   <li>{@link CollectionWorkflow} {@code ddcol-<collectionRef>}: Bacs presented a collection.
 *       PRESENTED until the due date, then paid (COMPLETED) or returned unpaid (RETURNED) with an
 *       ARUDD reason code: 0 refer to payer (no money, or the customer is frozen), 1 instruction
 *       cancelled, 6 no instruction, B account closed.</li>
 *   <li>{@link ClaimWorkflow} {@code ddclaim-<paymentId>}: a Direct Debit Guarantee refund. The
 *       bank refunds at once (REFUNDED) and claims the money back from the originator (DDICA).</li>
 * </ul>
 */
public final class DirectDebitWorkflows {

    static final ZoneId UK = ZoneId.of("Europe/London");
    /** Bacs debits are applied at the start of the entry day (day 3). */
    static final LocalTime ENTRY_TIME = LocalTime.of(6, 0);

    private DirectDebitWorkflows() {
    }

    static ActivityOptions options() {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(15))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setBackoffCoefficient(2.0)
                        .setMaximumAttempts(10)
                        .build())
                .build();
    }

    // ------------------------------------------------------------------------------------------

    public record MandateNotice(String mandateId, String mandateRef, String correlationId, String account,
                                String originatorName, String serviceUserNumber, String payerReference) {
    }

    public record Collection(String paymentId, String collectionRef, String mandateRef, String correlationId,
                             String account, String amount, String currency, String dueDate, String originatorName,
                             String serviceUserNumber, String payerReference, String receivedAt, String dueAt) {
    }

    public record Claim(String paymentId, String customerId, String correlationId, String reasonCode) {
    }

    /** customerStatus from the mapping service; mandateStatus ACTIVE, CANCELLED or NONE (no instruction). */
    public record Standing(String customerId, String customerStatus, String mandateStatus) {
    }

    /** ok, or the reason the claim is refused; collectionRef and customer as recorded. */
    public record Eligibility(boolean ok, String reason, String collectionRef) {
    }

    @ActivityInterface
    public interface DirectDebitActivities {

        /** Owner of the debited account (mapping service). */
        String accountOwner(String correlationId, String account);

        /** Inserts the mandate (ACTIVE) and publishes directdebit.mandate_created. Idempotent on the reference. */
        void recordMandate(MandateNotice notice, String customerId);

        /** Tells Essence (ADDACS to the originator), marks the mandate CANCELLED, publishes directdebit.mandate_cancelled. */
        void cancelMandate(MandateNotice notice, String customerId, String cancelledBy);

        /** Records the collection as a DIRECT_DEBIT payment in PRESENTED. Idempotent on the collection reference. */
        Standing present(Collection collection);

        /** Mandate and customer status as they are now (checked again on the due date). */
        Standing standing(Collection collection);

        /** Pays the collection in Essence. Non-retryable PAY-FUNDS-001 when there is not enough money. */
        void pay(Collection collection);

        /** Returns the collection unpaid in Essence with the ARUDD code. */
        void returnUnpaid(Collection collection, String reasonCode);

        /** PRESENTED → COMPLETED or RETURNED, with history and a payment event. */
        void settleCollection(Collection collection, String customerId, String status, String reason);

        /** A refund can be claimed for a paid Direct Debit on the customer's own account, once. */
        Eligibility claimable(Claim claim);

        /** Direct Debit Guarantee refund in Essence; COMPLETED → REFUNDED with history and a payment event. */
        void refund(Claim claim, String collectionRef);

        /** Publishes directdebit.claim_refused for the app. */
        void claimRefused(Claim claim, String reason);
    }

    // ------------------------------------------------------------------------------------------

    @WorkflowInterface
    public interface MandateWorkflow {

        @WorkflowMethod
        String run(MandateNotice notice);

        @SignalMethod
        void cancel(String requestedBy);
    }

    public static class MandateImpl implements MandateWorkflow {

        private final DirectDebitActivities activities = Workflow.newActivityStub(DirectDebitActivities.class, options());
        private String cancelledBy;

        @Override
        public String run(MandateNotice notice) {
            String customerId = activities.accountOwner(notice.correlationId(), notice.account());
            activities.recordMandate(notice, customerId);
            Workflow.await(() -> cancelledBy != null);
            activities.cancelMandate(notice, customerId, cancelledBy);
            return "CANCELLED";
        }

        @Override
        public void cancel(String requestedBy) {
            if (cancelledBy == null) {
                cancelledBy = requestedBy == null ? "customer" : requestedBy;
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    @WorkflowInterface
    public interface CollectionWorkflow {

        @WorkflowMethod
        String run(Collection collection);
    }

    public static class CollectionImpl implements CollectionWorkflow {

        private final DirectDebitActivities activities = Workflow.newActivityStub(DirectDebitActivities.class, options());

        @Override
        public String run(Collection c) {
            Standing presented = activities.present(c);
            // dueAt: the due date at the bank's Bacs processing time (pbp.directdebits.entry-time).
            long due = c.dueAt() != null ? java.time.Instant.parse(c.dueAt()).toEpochMilli()
                    : LocalDate.parse(c.dueDate()).atTime(ENTRY_TIME).atZone(UK).toInstant().toEpochMilli();
            long wait = due - Workflow.currentTimeMillis();
            if (wait > 0) {
                Workflow.sleep(Duration.ofMillis(wait));
            }
            Standing now = activities.standing(c);
            String customerId = presented.customerId();
            String code = null;
            String why = null;
            if ("NONE".equals(now.mandateStatus())) {
                code = "6";
                why = "no instruction";
            } else if (!"ACTIVE".equals(now.mandateStatus())) {
                code = "1";
                why = "instruction cancelled by payer";
            } else if ("CLOSED".equals(now.customerStatus())) {
                code = "B";
                why = "account closed";
            } else if (!"ACTIVE".equals(now.customerStatus())) {
                code = "0";
                why = "refer to payer (account under review)";
            }
            if (code == null) {
                try {
                    activities.pay(c);
                    activities.settleCollection(c, customerId, "COMPLETED", "Paid to " + c.originatorName());
                    return "COMPLETED";
                } catch (ActivityFailure failure) {
                    if (failure.getCause() instanceof ApplicationFailure app && "PAY-FUNDS-001".equals(app.getType())) {
                        code = "0";
                        why = "refer to payer (insufficient funds)";
                    } else {
                        throw failure;
                    }
                }
            }
            activities.returnUnpaid(c, code);
            activities.settleCollection(c, customerId, "RETURNED", "ARUDD " + code + ": " + why);
            return "RETURNED";
        }
    }

    // ------------------------------------------------------------------------------------------

    @WorkflowInterface
    public interface ClaimWorkflow {

        @WorkflowMethod
        String run(Claim claim);
    }

    public static class ClaimImpl implements ClaimWorkflow {

        private final DirectDebitActivities activities = Workflow.newActivityStub(DirectDebitActivities.class, options());

        @Override
        public String run(Claim claim) {
            Eligibility e = activities.claimable(claim);
            if (!e.ok()) {
                activities.claimRefused(claim, e.reason());
                return "REFUSED";
            }
            activities.refund(claim, e.collectionRef());
            return "REFUNDED";
        }
    }
}
