package bank.pbp.orchestrator.cards;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;

/**
 * Card controls saga (customer freezes the card, changes the daily limit or switches channels).
 * Thredd first, then the mapping service, whose ref.status_changed event makes the cache updater
 * rewrite card:{token}; the EHI responder enforces the new controls within about 5 s.
 * Workflow ID {@code card-controls-<command ID>}; task queue "cards".
 */
@WorkflowInterface
public interface CardControlsWorkflow {

    @WorkflowMethod
    String apply(CardControls controls);

    record CardControls(String commandId, String correlationId, String customerId, String token, String refId,
                        boolean frozen, String dailyLimit, boolean online, boolean contactless, boolean atm) {
    }

    @ActivityInterface
    interface CardActivities {

        void updateThredd(CardControls controls);

        void updateMapping(CardControls controls);

        void publishChanged(CardControls controls);
    }

    class Impl implements CardControlsWorkflow {

        private final CardActivities activities = Workflow.newActivityStub(CardActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(15))
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setInitialInterval(Duration.ofSeconds(1))
                                .setBackoffCoefficient(2.0)
                                .setMaximumAttempts(8)
                                .build())
                        .build());

        @Override
        public String apply(CardControls controls) {
            activities.updateThredd(controls);
            activities.updateMapping(controls);
            activities.publishChanged(controls);
            return controls.frozen() ? "BLOCKED" : "ACTIVE";
        }
    }
}
