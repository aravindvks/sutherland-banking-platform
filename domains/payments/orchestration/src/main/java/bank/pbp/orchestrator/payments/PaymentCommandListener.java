package bank.pbp.orchestrator.payments;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.orchestrator.directdebits.DirectDebitCommands;
import bank.pbp.orchestrator.schedules.ScheduleCommands;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Customer commands on payment-commands (LLD section 9), keyed by customer ID:
 * <ul>
 *   <li>{@code payment.create} → payment saga. The workflow ID is the business key, so a
 *       redelivered command finds the running workflow instead of starting a second one.</li>
 *   <li>{@code schedule.create}, {@code schedule.cancel}, {@code schedule.run_now} → standing orders
 *       and scheduled payments.</li>
 *   <li>{@code directdebit.cancel}, {@code directdebit.claim} → Direct Debit mandates and refunds.</li>
 * </ul>
 */
@Component
public class PaymentCommandListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandListener.class);

    private final WorkflowClient temporal;
    private final EventPublisher events;
    private final ScheduleCommands schedules;
    private final DirectDebitCommands directDebits;
    private final String bankSortCode;

    public PaymentCommandListener(WorkflowClient temporal, EventPublisher events, ScheduleCommands schedules,
                                  DirectDebitCommands directDebits,
                                  @Value("${pbp.bank.sort-code}") String bankSortCode) {
        this.temporal = temporal;
        this.events = events;
        this.schedules = schedules;
        this.directDebits = directDebits;
        this.bankSortCode = bankSortCode;
    }

    @KafkaListener(topics = Topics.PAYMENT_COMMANDS, groupId = "orchestrator-payments")
    public void onCommand(String message) {
        EventEnvelope event = events.read(message);
        switch (event.type()) {
            case "payment.create" -> startPayment(event);
            case "schedule.create" -> schedules.create(event);
            case "schedule.cancel" -> schedules.cancel(event);
            case "schedule.run_now" -> schedules.runNow(event);
            case "directdebit.cancel" -> directDebits.cancel(event);
            case "directdebit.claim" -> directDebits.claim(event);
            default -> log.info("command.ignored type={}", event.type());
        }
    }

    private void startPayment(EventEnvelope event) {
        PaymentCommand command = toCommand(event);
        PaymentWorkflow workflow = temporal.newWorkflowStub(PaymentWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.PAYMENTS)
                .setWorkflowId("payment-" + command.paymentId())
                .build());
        try {
            WorkflowClient.start(workflow::run, command);
            log.info("workflow.started workflowId=payment-{} type={}", command.paymentId(), command.paymentType());
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            log.info("workflow.duplicate workflowId=payment-{} (command redelivered)", command.paymentId());
        }
    }

    /**
     * The orchestrator decides the rail: this bank's sort code → INTERNAL; otherwise CHAPS when the
     * customer chose it, else Faster Payments.
     */
    public static String paymentType(String bankSortCode, String creditorSortCode, String requestedScheme) {
        if (bankSortCode.equals(creditorSortCode)) {
            return "INTERNAL";
        }
        return "CHAPS".equals(requestedScheme) ? "CHAPS" : "FPS";
    }

    @SuppressWarnings("unchecked")
    PaymentCommand toCommand(EventEnvelope event) {
        Map<String, Object> d = event.data();
        Map<String, Object> creditor = (Map<String, Object>) d.get("creditor");
        Map<String, Object> amount = (Map<String, Object>) d.get("amount");
        String sortCode = str(creditor.get("sortCode"));
        return new PaymentCommand(
                str(d.get("paymentId")),
                event.customerId(),
                event.correlationId(),
                str(d.get("debtorAccount")),
                str(creditor.get("name")),
                sortCode,
                str(creditor.get("accountNumber")),
                str(amount.get("value")),
                str(amount.get("currency")),
                str(d.get("remittanceInfo")),
                paymentType(bankSortCode, sortCode, str(d.get("scheme"))),
                str(d.get("idempotencyKey")),
                str(d.get("initiatedAt")),
                str(d.get("copResult")),
                null);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
