package bank.pbp.orchestrator.cards;

import bank.pbp.orchestrator.temporal.TaskQueues;
import bank.pbp.orchestrator.temporal.WorkerRegistration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The debit cards domain on Temporal: card controls. Shares the "cards" task queue with credit cards. */
@Configuration
public class CardsWorkers {

    @Bean
    WorkerRegistration cardsWorkers(CardActivitiesImpl cardActivities) {
        return new WorkerRegistration("cards", TaskQueues.CARDS,
                List.of(CardControlsWorkflow.Impl.class), List.of(cardActivities));
    }
}
