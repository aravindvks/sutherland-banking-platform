package bank.pbp.orchestrator.creditcards;

import bank.pbp.orchestrator.temporal.TaskQueues;
import bank.pbp.orchestrator.temporal.WorkerRegistration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The credit cards domain on Temporal. Shares the "cards" task queue with debit cards. */
@Configuration
public class CreditCardsWorkers {

    @Bean
    WorkerRegistration creditCardsWorkerRegistration(CreditCardActivitiesImpl creditCardActivities) {
        return new WorkerRegistration("creditcards", TaskQueues.CARDS,
                List.of(CreditCardWorkflows.ApplicationImpl.class, CreditCardWorkflows.RepaymentImpl.class,
                        CreditCardWorkflows.StatementImpl.class, CreditCardWorkflows.WithdrawalImpl.class),
                List.of(creditCardActivities));
    }
}
