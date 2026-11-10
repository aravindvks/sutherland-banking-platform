package bank.pbp.orchestrator.directdebits;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.ids.Ids;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.Claim;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.ClaimWorkflow;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.Collection;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.CollectionWorkflow;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.MandateNotice;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows.MandateWorkflow;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Starts and signals the Direct Debit sagas: from Finastra notices on payment-events (a new
 * mandate, a presented collection) and from customer commands on payment-commands (cancel a
 * mandate, claim a refund). Customer commands are refused unless the mandate or payment is theirs.
 */
@Component
public class DirectDebitCommands {

    private static final Logger log = LoggerFactory.getLogger(DirectDebitCommands.class);

    private final WorkflowClient temporal;
    private final JdbcClient jdbc;
    private final java.time.LocalTime entryTime;

    public DirectDebitCommands(WorkflowClient temporal, JdbcClient jdbc,
                               @org.springframework.beans.factory.annotation.Value("${pbp.directdebits.entry-time:06:00}")
                               String entryTime) {
        this.temporal = temporal;
        this.jdbc = jdbc;
        this.entryTime = java.time.LocalTime.parse(entryTime);
    }

    /** directdebit.mandate_received (essence-adapter). */
    public void mandateReceived(EventEnvelope e) {
        Map<String, Object> d = e.data();
        MandateNotice n = new MandateNotice(Ids.uuidV7().toString(), str(d.get("mandateRef")), e.correlationId(),
                str(d.get("account")), str(d.get("originatorName")), str(d.get("serviceUserNumber")),
                str(d.get("payerReference")));
        start(MandateWorkflow.class, "ddmandate-" + n.mandateRef(), wf -> WorkflowClient.start(wf::run, n));
    }

    /** directdebit.collection_presented (essence-adapter). */
    @SuppressWarnings("unchecked")
    public void collectionPresented(EventEnvelope e) {
        Map<String, Object> d = e.data();
        Map<String, Object> amount = (Map<String, Object>) d.get("amount");
        Collection c = new Collection(Ids.uuidV7().toString(), str(d.get("collectionRef")), str(d.get("mandateRef")),
                e.correlationId(), str(d.get("account")), str(amount.get("value")), str(amount.get("currency")),
                str(d.get("dueDate")), str(d.get("originatorName")), str(d.get("serviceUserNumber")),
                str(d.get("payerReference")), Instant.now().truncatedTo(ChronoUnit.MICROS).toString(),
                java.time.LocalDate.parse(str(d.get("dueDate"))).atTime(entryTime)
                        .atZone(java.time.ZoneId.of("Europe/London")).toInstant().toString());
        start(CollectionWorkflow.class, "ddcol-" + c.collectionRef(), wf -> WorkflowClient.start(wf::run, c));
    }

    /** directdebit.cancel (BFF, the customer). */
    public void cancel(EventEnvelope e) {
        String mandateId = str(e.data().get("mandateId"));
        Optional<Map<String, Object>> m = jdbc.sql("""
                        SELECT mandate_ref AS ref, customer_id::text AS customer, status
                        FROM dd_mandate WHERE mandate_id = :id""")
                .param("id", UUID.fromString(mandateId)).query().listOfRows().stream().findFirst();
        if (m.isEmpty() || !String.valueOf(m.get().get("customer")).equals(e.customerId())) {
            log.warn("directdebit.cancel_refused mandateId={} customerId={} (not found or not theirs)", mandateId,
                    e.customerId());
            return;
        }
        String ref = String.valueOf(m.get().get("ref"));
        try {
            temporal.newWorkflowStub(MandateWorkflow.class, "ddmandate-" + ref).cancel("customer");
            log.info("directdebit.cancel_requested mandateRef={}", ref);
        } catch (WorkflowNotFoundException ex) {
            log.warn("directdebit.no_running_workflow mandateRef={}", ref);
        }
    }

    /** directdebit.claim (BFF, the customer): a Direct Debit Guarantee refund. */
    public void claim(EventEnvelope e) {
        Claim claim = new Claim(str(e.data().get("paymentId")), e.customerId(), e.correlationId(),
                str(e.data().getOrDefault("reasonCode", "1")));
        start(ClaimWorkflow.class, "ddclaim-" + claim.paymentId(), wf -> WorkflowClient.start(wf::run, claim));
    }

    private <W> void start(Class<W> type, String workflowId, java.util.function.Consumer<W> starter) {
        W wf = temporal.newWorkflowStub(type, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.PAYMENTS)
                .setWorkflowId(workflowId)
                .build());
        try {
            starter.accept(wf);
            log.info("workflow.started workflowId={}", workflowId);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            log.info("workflow.duplicate workflowId={}", workflowId);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
