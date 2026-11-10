package bank.pbp.orchestrator.creditcards;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.Application;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.ApplicationWorkflow;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.Repayment;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.RepaymentWorkflow;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.StatementNotice;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.StatementWorkflow;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.Withdrawal;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.WithdrawalWorkflow;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Starts and signals the credit card sagas: customer commands on card-commands
 * ({@code creditcard.apply}, {@code .accept}, {@code .repay}, {@code .withdraw}) and Thredd's
 * statements on card-events ({@code creditcard.statement_issued}, from the Thredd adapter).
 * A command for an application or card that is not the customer's is refused.
 */
@Component
public class CreditCardCommands {

    private static final Logger log = LoggerFactory.getLogger(CreditCardCommands.class);

    private final WorkflowClient temporal;
    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final String referralTimeout;
    private final String offerValidity;

    public CreditCardCommands(WorkflowClient temporal, JdbcClient jdbc, EventPublisher events,
                              @Value("${pbp.credit.referral-timeout}") String referralTimeout,
                              @Value("${pbp.credit.offer-validity}") String offerValidity) {
        this.temporal = temporal;
        this.jdbc = jdbc;
        this.events = events;
        this.referralTimeout = referralTimeout;
        this.offerValidity = offerValidity;
    }

    @KafkaListener(topics = Topics.CARD_COMMANDS, groupId = "orchestrator-credit-cards")
    public void onCommand(String message) {
        EventEnvelope e = events.read(message);
        switch (e.type()) {
            case "creditcard.apply" -> apply(e);
            case "creditcard.accept" -> accept(e);
            case "creditcard.repay" -> repay(e);
            case "creditcard.withdraw" -> withdraw(e);
            default -> {
                // card.controls.update and others belong to other listeners.
            }
        }
    }

    @KafkaListener(topics = Topics.CARD_EVENTS, groupId = "orchestrator-credit-statements")
    public void onCardEvent(String message) {
        EventEnvelope e = events.read(message);
        if (!"creditcard.statement_issued".equals(e.type())) {
            return;
        }
        Map<String, Object> d = e.data();
        StatementNotice s = new StatementNotice(str(d.get("statementId")), str(d.get("accountId")),
                e.correlationId() != null ? e.correlationId() : Ids.newCorrelationId(JourneyType.CRD),
                str(d.get("statementDate")), str(d.get("balance")), str(d.get("minimumPayment")), str(d.get("interest")),
                str(d.get("dueDate")), str(d.get("dueAt")));
        start(StatementWorkflow.class, "ccstmt-" + s.statementId(), wf -> WorkflowClient.start(wf::run, s));
    }

    void apply(EventEnvelope e) {
        Map<String, Object> d = e.data();
        Application a = new Application(str(d.get("applicationId")), e.customerId(), e.correlationId(),
                str(d.get("name")), str(d.get("annualIncome")), str(d.get("employmentStatus")),
                str(d.getOrDefault("monthlyHousing", "0")), str(d.getOrDefault("monthlyCommitments", "0")),
                str(d.get("requestedLimit")));
        start(ApplicationWorkflow.class, "ccapp-" + a.applicationId(),
                wf -> WorkflowClient.start(wf::run, a, referralTimeout, offerValidity));
    }

    void accept(EventEnvelope e) {
        String applicationId = str(e.data().get("applicationId"));
        Optional<String> owner = jdbc.sql("SELECT customer_id::text FROM credit_application WHERE application_id = :id")
                .param("id", UUID.fromString(applicationId)).query(String.class).optional();
        if (owner.isEmpty() || !owner.get().equals(e.customerId())) {
            log.warn("creditcard.accept_refused applicationId={} customerId={} (not found or not theirs)", applicationId,
                    e.customerId());
            return;
        }
        signal(applicationId, wf -> wf.accept(str(e.data().getOrDefault("agreementVersion", "v1"))));
    }

    void repay(EventEnvelope e) {
        Map<String, Object> d = e.data();
        Repayment r = new Repayment(str(d.get("repaymentId")), e.customerId(), e.correlationId(), str(d.get("accountId")),
                str(d.get("debitAccount")), str(d.get("amount")));
        start(RepaymentWorkflow.class, "ccrepay-" + r.repaymentId(), wf -> WorkflowClient.start(wf::run, r));
    }

    void withdraw(EventEnvelope e) {
        Withdrawal w = new Withdrawal(str(e.data().get("accountId")), e.customerId(), e.correlationId());
        start(WithdrawalWorkflow.class, "ccwithdraw-" + w.accountId(), wf -> WorkflowClient.start(wf::run, w));
    }

    /** Signals an application saga; used by the customer's accept and the underwriter's decision. */
    public boolean signal(String applicationId, Consumer<ApplicationWorkflow> signal) {
        try {
            signal.accept(temporal.newWorkflowStub(ApplicationWorkflow.class, "ccapp-" + applicationId));
            return true;
        } catch (WorkflowNotFoundException ex) {
            log.warn("creditcard.no_running_workflow applicationId={}", applicationId);
            return false;
        }
    }

    private <W> void start(Class<W> type, String workflowId, Consumer<W> starter) {
        W wf = temporal.newWorkflowStub(type, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.CARDS)
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
