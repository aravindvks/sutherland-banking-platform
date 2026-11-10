package bank.pbp.orchestrator.schedules;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.orchestrator.schedules.ScheduleWorkflow.ScheduleRequest;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** schedule.* commands from payment-commands: start, cancel, or (demo) run the next payment now. */
@Component
public class ScheduleCommands {

    private static final Logger log = LoggerFactory.getLogger(ScheduleCommands.class);

    private final WorkflowClient temporal;
    private final JdbcClient jdbc;

    public ScheduleCommands(WorkflowClient temporal, JdbcClient jdbc) {
        this.temporal = temporal;
        this.jdbc = jdbc;
    }

    @SuppressWarnings("unchecked")
    public void create(EventEnvelope e) {
        Map<String, Object> d = e.data();
        Map<String, Object> creditor = (Map<String, Object>) d.get("creditor");
        Map<String, Object> amount = (Map<String, Object>) d.get("amount");
        ScheduleRequest r = new ScheduleRequest(str(d.get("scheduleId")), e.customerId(), e.correlationId(),
                str(d.get("kind")), str(d.get("frequency")), str(d.get("startDate")), str(d.get("endDate")),
                str(d.get("debtorAccount")), str(creditor.get("name")), str(creditor.get("sortCode")),
                str(creditor.get("accountNumber")), str(amount.get("value")), str(amount.get("currency")),
                str(d.get("reference")), str(d.get("scheme")), str(d.get("copResult")));
        ScheduleWorkflow wf = temporal.newWorkflowStub(ScheduleWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.PAYMENTS)
                .setWorkflowId("schedule-" + r.scheduleId())
                .build());
        try {
            WorkflowClient.start(wf::run, r, 0);
            log.info("workflow.started workflowId=schedule-{} kind={}", r.scheduleId(), r.kind());
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            log.info("workflow.duplicate workflowId=schedule-{}", r.scheduleId());
        }
    }

    public void cancel(EventEnvelope e) {
        owned(e).ifPresent(id -> signal(id, wf -> wf.cancel("customer")));
    }

    public void runNow(EventEnvelope e) {
        owned(e).ifPresent(id -> signal(id, ScheduleWorkflow::runNow));
    }

    /** The schedule, only when it belongs to the customer who sent the command. */
    private Optional<String> owned(EventEnvelope e) {
        String id = str(e.data().get("scheduleId"));
        Optional<String> owner = jdbc.sql("SELECT customer_id::text FROM payment_schedule WHERE schedule_id = :id")
                .param("id", UUID.fromString(id)).query(String.class).optional();
        if (owner.isEmpty() || !owner.get().equals(e.customerId())) {
            log.warn("schedule.command_refused type={} scheduleId={} customerId={} (not found or not theirs)", e.type(), id,
                    e.customerId());
            return Optional.empty();
        }
        return Optional.of(id);
    }

    private void signal(String scheduleId, java.util.function.Consumer<ScheduleWorkflow> action) {
        try {
            action.accept(temporal.newWorkflowStub(ScheduleWorkflow.class, "schedule-" + scheduleId));
        } catch (WorkflowNotFoundException ex) {
            log.warn("schedule.no_running_workflow scheduleId={}", scheduleId);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
