package bank.pbp.orchestrator.cards;

import static bank.pbp.orchestrator.temporal.ActivitySupport.call;
import static bank.pbp.orchestrator.temporal.ActivitySupport.withCorrelation;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import bank.pbp.orchestrator.cards.CardControlsWorkflow.CardControls;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Card controls activities, and the card-commands listener that starts the saga. */
@Component
public class CardActivitiesImpl implements CardControlsWorkflow.CardActivities {

    private static final Logger log = LoggerFactory.getLogger(CardActivitiesImpl.class);

    private final RestClient thredd;
    private final RestClient mapping;
    private final EventPublisher events;
    private final WorkflowClient temporal;

    public CardActivitiesImpl(@Qualifier("threddClient") RestClient thredd,
                              @Qualifier("mappingClient") RestClient mapping,
                              EventPublisher events, WorkflowClient temporal) {
        this.thredd = thredd;
        this.mapping = mapping;
        this.events = events;
        this.temporal = temporal;
    }

    @KafkaListener(topics = Topics.CARD_COMMANDS, groupId = "orchestrator-cards")
    public void onCommand(String message) {
        EventEnvelope e = events.read(message);
        if (!"card.controls.update".equals(e.type())) {
            return;
        }
        Map<String, Object> d = e.data();
        CardControls c = new CardControls(str(d.get("commandId")),
                e.correlationId() != null ? e.correlationId() : Ids.newCorrelationId(JourneyType.CRD),
                e.customerId(), str(d.get("token")), str(d.get("refId")), bool(d.get("frozen")),
                str(d.get("dailyLimit")), bool(d.get("online")), bool(d.get("contactless")), bool(d.get("atm")));
        CardControlsWorkflow wf = temporal.newWorkflowStub(CardControlsWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.CARDS)
                .setWorkflowId("card-controls-" + c.commandId())
                .build());
        try {
            WorkflowClient.start(wf::apply, c);
            log.info("workflow.started workflowId=card-controls-{}", c.commandId());
        } catch (WorkflowExecutionAlreadyStarted duplicate) {
            log.info("workflow.duplicate workflowId=card-controls-{}", c.commandId());
        }
    }

    @Override
    public void updateThredd(CardControls c) {
        withCorrelation(c.correlationId(), () -> {
            call("thredd.cards.status", () -> thredd.post().uri("/v1/cards/{token}/status", c.token())
                    .header("Idempotency-Key", c.commandId() + ":status")
                    .body(Map.of("status", c.frozen() ? "BLOCKED" : "ACTIVE")).retrieve().toBodilessEntity());
            return call("thredd.cards.controls", () -> thredd.put().uri("/v1/cards/{token}/controls", c.token())
                    .header("Idempotency-Key", c.commandId() + ":controls")
                    .body(Map.of("dailyLimit", c.dailyLimit(), "online", c.online(), "contactless", c.contactless(),
                            "atm", c.atm()))
                    .retrieve().toBodilessEntity());
        });
    }

    @Override
    public void updateMapping(CardControls c) {
        withCorrelation(c.correlationId(), () -> call("mapping.refs.update", () -> mapping.patch()
                .uri("/v1/refs/{id}", c.refId())
                .header("X-Actor", "user:" + c.customerId())
                .body(Map.of("status", c.frozen() ? "BLOCKED" : "ACTIVE",
                        "metadata", Map.of("dailyLimit", c.dailyLimit(), "online", c.online(),
                                "contactless", c.contactless(), "atm", c.atm())))
                .retrieve().toBodilessEntity()));
    }

    @Override
    public void publishChanged(CardControls c) {
        withCorrelation(c.correlationId(), () -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("cardToken", c.token());
            data.put("status", c.frozen() ? "BLOCKED" : "ACTIVE");
            data.put("dailyLimit", c.dailyLimit());
            data.put("online", c.online());
            data.put("contactless", c.contactless());
            data.put("atm", c.atm());
            events.publish(Topics.CARD_EVENTS, EventEnvelope.of("card.controls_changed", "orchestrator",
                    c.correlationId(), null, c.customerId(), data));
            return null;
        });
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean bool(Object o) {
        return o != null && Boolean.parseBoolean(o.toString());
    }
}
