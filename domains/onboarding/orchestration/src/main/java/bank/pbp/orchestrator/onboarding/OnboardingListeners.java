package bank.pbp.orchestrator.onboarding;

import bank.pbp.common.error.PbpException;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.orchestrator.compliance.ComplianceWorkflow;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingCommand;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingStatus;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starts onboarding sagas from onboarding-commands, passes Atfinity outcomes from kyc-events to
 * the waiting saga as a kycOutcome signal, and answers status queries for the BFF.
 */
@RestController
public class OnboardingListeners {

    private static final Logger log = LoggerFactory.getLogger(OnboardingListeners.class);

    private final WorkflowClient temporal;
    private final EventPublisher events;
    private final JdbcClient jdbc;

    public OnboardingListeners(WorkflowClient temporal, EventPublisher events, JdbcClient jdbc) {
        this.temporal = temporal;
        this.events = events;
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = Topics.ONBOARDING_COMMANDS, groupId = "orchestrator-onboarding")
    public void onCommand(String message) {
        EventEnvelope e = events.read(message);
        if (!"onboarding.start".equals(e.type())) {
            return;
        }
        Map<String, Object> d = e.data();
        OnboardingCommand cmd = new OnboardingCommand(str(d.get("onboardingId")), e.correlationId(),
                str(d.get("customerType")), str(d.get("name")), str(d.get("email")));
        OnboardingWorkflow wf = temporal.newWorkflowStub(OnboardingWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.ONBOARDING)
                .setWorkflowId("onboarding-" + cmd.onboardingId())
                .build());
        try {
            WorkflowClient.start(wf::run, cmd);
            log.info("workflow.started workflowId=onboarding-{}", cmd.onboardingId());
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            log.info("workflow.duplicate workflowId=onboarding-{}", cmd.onboardingId());
        }
    }

    @KafkaListener(topics = Topics.KYC_EVENTS, groupId = "orchestrator-kyc")
    public void onKyc(String message) {
        EventEnvelope e = events.read(message);
        if ("kyc.rescreen.hit".equals(e.type())) {
            startFreeze(e);
            return;
        }
        String outcome = switch (e.type()) {
            case "kyc.approved" -> "approved";
            case "kyc.referred" -> "referred";
            case "kyc.rejected" -> "rejected";
            default -> null;
        };
        if (outcome == null) {
            return;
        }
        String customerId = str(e.data().get("customerId"));
        Optional<String> sagaId = jdbc.sql("""
                        SELECT saga_id::text FROM saga
                        WHERE customer_id = CAST(:customerId AS uuid) AND saga_type = 'ONBOARDING'
                          AND status IN ('RUNNING','WAITING')
                        ORDER BY started_at DESC LIMIT 1""")
                .param("customerId", customerId)
                .query(String.class)
                .optional();
        if (sagaId.isEmpty()) {
            log.warn("kyc.no_waiting_saga customerId={} type={}", customerId, e.type());
            return;
        }
        temporal.newWorkflowStub(OnboardingWorkflow.class, "onboarding-" + sagaId.get())
                .kycOutcome(outcome, str(e.data().get("riskRating")));
        log.info("kyc.signalled workflowId=onboarding-{} outcome={}", sagaId.get(), outcome);
    }

    /** Sanctions or PEP rescreening hit: freeze the customer and block cards (compliance saga). */
    private void startFreeze(EventEnvelope e) {
        String customerId = str(e.data().get("customerId"));
        String correlationId = e.correlationId() != null ? e.correlationId()
                : bank.pbp.common.ids.Ids.newCorrelationId(bank.pbp.common.ids.JourneyType.CUS);
        String vendorEventId = str(e.data().get("vendorEventId"));
        // One saga per screening event: a repeated webhook finds the same workflow.
        String sagaId = java.util.UUID.nameUUIDFromBytes(("compliance:" + vendorEventId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        ComplianceWorkflow wf = temporal.newWorkflowStub(ComplianceWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.COMPLIANCE)
                .setWorkflowId("compliance-" + sagaId)
                .build());
        try {
            WorkflowClient.start(wf::freeze, sagaId, customerId, correlationId, "Atfinity rescreening hit");
            log.info("workflow.started workflowId=compliance-{} customerId={}", sagaId, customerId);
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            log.info("workflow.duplicate workflowId=compliance-{}", sagaId);
        }
    }

    @GetMapping("/v1/onboarding/{onboardingId}")
    public OnboardingStatus status(@PathVariable String onboardingId) {
        try {
            return temporal.newWorkflowStub(OnboardingWorkflow.class, "onboarding-" + onboardingId).status();
        } catch (WorkflowNotFoundException e) {
            throw new PbpException("GEN-NOTFOUND-001", 404, "Not found", "No onboarding " + onboardingId);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
