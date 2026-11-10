package bank.pbp.orchestrator.payments;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Payment saga following the LLD state machine:
 * INITIATED → CHECKS_PASSED → [HELD] → [QUEUED] → SUBMITTED → [ACCEPTED] → COMPLETED,
 * or REJECTED / FAILED.
 *
 * <ul>
 *   <li>Checks: limits for the scheme, then Confirmation of Payee. NO_MATCH is rejected; CLOSE_MATCH
 *       goes ahead only when the customer confirmed the payee; UNAVAILABLE goes ahead (bank policy).</li>
 *   <li>HELD: high-value payments wait for a staff APPROVE or REJECT (72 hours, then rejected).</li>
 *   <li>QUEUED: CHAPS outside UK business hours waits for the next opening.</li>
 *   <li>Same-bank (INTERNAL) payments post directly in Essence. FPS and CHAPS go to Finastra,
 *       which reports back through the paymentStatus signal. The CHAPS fee is charged on completion.</li>
 * </ul>
 */
public class PaymentWorkflowImpl implements PaymentWorkflow {

    private final PaymentActivities activities = Workflow.newActivityStub(PaymentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(8)
                            .build())
                    .build());

    /** Confirmation of Payee is answered in seconds or treated as unavailable: no long retries. */
    private final PaymentActivities cop = Workflow.newActivityStub(PaymentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    /** Wait for a final scheme status (payment.status-timeout.fps, LLD section 13). */
    static final Duration SCHEME_TIMEOUT = Duration.ofMinutes(2);
    /** How long a HELD payment waits for staff before it is rejected. */
    static final Duration REVIEW_TIMEOUT = Duration.ofHours(72);

    private String status = "NEW";
    private final List<String[]> schemeUpdates = new ArrayList<>();
    private String[] review;

    @Override
    public String run(PaymentCommand command) {
        activities.createPayment(command);
        status = "INITIATED";

        PaymentActivities.RiskResult risk = activities.riskCheck(command);
        if (!risk.passed()) {
            return moveTo(command, "REJECTED", risk.code() + ": " + risk.reason());
        }

        PaymentActivities.CopCheck check;
        try {
            check = cop.confirmPayee(command);
        } catch (ActivityFailure failure) {
            check = new PaymentActivities.CopCheck("UNAVAILABLE", null, "PAY-COP-002");
        }
        if (check.result() == null) {
            check = new PaymentActivities.CopCheck("UNAVAILABLE", null, "PAY-COP-002");
        }
        switch (check.result()) {
            case "NO_MATCH" -> {
                return moveTo(command, "REJECTED",
                        "PAY-COP-001: the name does not match the account holder (" + check.reasonCode() + ")");
            }
            case "CLOSE_MATCH" -> {
                if (!"CLOSE_MATCH".equals(command.copResult())) {
                    return moveTo(command, "REJECTED", "PAY-COP-001: close match; the account is held by "
                            + check.matchedName() + ". Confirm the payee to continue");
                }
            }
            default -> {
                // MATCH, or UNAVAILABLE: the payment goes ahead and the result is on record.
            }
        }
        moveTo(command, "CHECKS_PASSED", "Confirmation of Payee: " + check.result()
                + ("UNAVAILABLE".equals(check.result()) ? " (paid under bank policy)" : ""));

        if (risk.reviewRequired()) {
            moveTo(command, "HELD", "PAY-HOLD-001: high-value payment needs staff approval");
            boolean decided = Workflow.await(REVIEW_TIMEOUT, () -> review != null);
            if (!decided) {
                return moveTo(command, "REJECTED", "PAY-HOLD-001: not reviewed within 72 hours");
            }
            if (!"APPROVE".equals(review[0])) {
                return moveTo(command, "REJECTED", "Rejected by " + review[1] + (review[2] == null ? "" : ": " + review[2]));
            }
        }
        String approval = review == null ? null : "Approved by " + review[1];

        if (!"INTERNAL".equals(command.paymentType())) {
            return otherBank(command, risk.fee(), approval);
        }

        moveTo(command, "SUBMITTED", approval);
        try {
            String postingRef = activities.postInternalTransfer(command);
            return moveTo(command, "COMPLETED", "Essence posting " + postingRef);
        } catch (ActivityFailure failure) {
            if (failure.getCause() instanceof ApplicationFailure app && app.isNonRetryable()) {
                return moveTo(command, "REJECTED", app.getType() + ": " + app.getOriginalMessage());
            }
            return moveTo(command, "FAILED", "Essence did not complete the transfer after retries");
        }
    }

    private String otherBank(PaymentCommand command, String fee, String approval) {
        if ("CHAPS".equals(command.paymentType())) {
            PaymentActivities.Window window = activities.chapsWindow();
            if (!window.open()) {
                moveTo(command, "QUEUED", "CHAPS is closed; the payment will be sent when it opens at " + window.opensAt());
                Workflow.sleep(Duration.ofMillis(window.waitMillis()));
            }
        }
        String finastraRef;
        try {
            finastraRef = activities.submitPayment(command);
        } catch (ActivityFailure failure) {
            if (failure.getCause() instanceof ApplicationFailure app && app.isNonRetryable()) {
                return moveTo(command, "REJECTED", app.getType() + ": " + app.getOriginalMessage());
            }
            return moveTo(command, "FAILED", "Finastra did not accept the payment after retries");
        }
        moveTo(command, "SUBMITTED", "Finastra reference " + finastraRef + (approval == null ? "" : " · " + approval));
        while (true) {
            boolean updated = Workflow.await(SCHEME_TIMEOUT, () -> !schemeUpdates.isEmpty());
            if (!updated) {
                return moveTo(command, "FAILED",
                        "PAY-STATUS-001: no final scheme status within " + SCHEME_TIMEOUT.toMinutes() + " min");
            }
            String[] update = schemeUpdates.remove(0);
            switch (update[0]) {
                case "ACCEPTED" -> {
                    if ("SUBMITTED".equals(status)) {
                        moveTo(command, "ACCEPTED", null);
                    }
                }
                case "COMPLETED" -> {
                    String note = "Settled by " + ("CHAPS".equals(command.paymentType()) ? "CHAPS" : "the scheme");
                    if (fee != null && new BigDecimal(fee).signum() > 0) {
                        activities.chargeFee(command, fee);
                        note += "; fee £" + fee + " charged";
                    }
                    return moveTo(command, "COMPLETED", note);
                }
                case "REJECTED" -> {
                    return moveTo(command, "REJECTED", "PAY-SCHEME-001: " + update[1]);
                }
                default -> {
                    // Unknown scheme statuses are ignored.
                }
            }
        }
    }

    @Override
    public void paymentStatus(String schemeStatus, String reason) {
        schemeUpdates.add(new String[] {schemeStatus, reason});
    }

    @Override
    public void reviewDecision(String decision, String reviewer, String note) {
        if (review == null && "HELD".equals(status)) {
            review = new String[] {decision, reviewer, note};
        }
    }

    @Override
    public String currentStatus() {
        return status;
    }

    private String moveTo(PaymentCommand command, String to, String reason) {
        activities.transition(command, status, to, reason);
        status = to;
        return status;
    }
}
