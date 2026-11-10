package bank.pbp.orchestrator.payments;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import bank.pbp.orchestrator.directdebits.DirectDebitCommands;
import bank.pbp.orchestrator.payments.InboundPaymentWorkflow.InboundCredit;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Adapter-normalised Finastra events on payment-events (LLD section 9):
 * {@code payment.scheme_status} becomes a paymentStatus signal to the waiting payment saga;
 * {@code credit.received} (Faster Payments or Bacs) starts an inbound payment saga;
 * {@code directdebit.mandate_received} and {@code directdebit.collection_presented} start the
 * Direct Debit sagas. The orchestrator's own payment.* events
 * on the same topic are ignored here.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final WorkflowClient temporal;
    private final EventPublisher events;
    private final DirectDebitCommands directDebits;

    public PaymentEventListener(WorkflowClient temporal, EventPublisher events, DirectDebitCommands directDebits) {
        this.temporal = temporal;
        this.events = events;
        this.directDebits = directDebits;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "orchestrator-payment-status")
    public void onEvent(String message) {
        EventEnvelope e = events.read(message);
        switch (e.type()) {
            case "payment.scheme_status" -> schemeStatus(e.data());
            case "credit.received" -> creditReceived(e);
            case "directdebit.mandate_received" -> directDebits.mandateReceived(e);
            case "directdebit.collection_presented" -> directDebits.collectionPresented(e);
            default -> {
                // Our own events; nothing to do.
            }
        }
    }

    private void schemeStatus(Map<String, Object> d) {
        String paymentId = str(d.get("paymentId"));
        try {
            temporal.newWorkflowStub(PaymentWorkflow.class, "payment-" + paymentId)
                    .paymentStatus(str(d.get("status")), str(d.get("reason")));
            log.info("payment.signalled paymentId={} schemeStatus={}", paymentId, d.get("status"));
        } catch (WorkflowNotFoundException ex) {
            log.warn("payment.no_running_workflow paymentId={} schemeStatus={}", paymentId, d.get("status"));
        }
    }

    @SuppressWarnings("unchecked")
    private void creditReceived(EventEnvelope e) {
        Map<String, Object> d = e.data();
        Map<String, Object> amount = (Map<String, Object>) d.get("amount");
        InboundCredit credit = new InboundCredit(Ids.uuidV7().toString(), str(d.get("finastraRef")),
                e.correlationId() != null ? e.correlationId() : Ids.newCorrelationId(JourneyType.PAY),
                str(d.get("creditAccount")), str(amount.get("value")), str(amount.get("currency")),
                str(d.get("debtorName")), str(d.get("debtorSortCode")), str(d.get("reference")),
                str(d.get("scheme")), Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        InboundPaymentWorkflow wf = temporal.newWorkflowStub(InboundPaymentWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.PAYMENTS)
                .setWorkflowId("inbound-" + credit.finastraRef())
                .build());
        try {
            WorkflowClient.start(wf::receive, credit);
            log.info("workflow.started workflowId=inbound-{} account={}", credit.finastraRef(), credit.creditAccount());
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            log.info("workflow.duplicate workflowId=inbound-{}", credit.finastraRef());
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
