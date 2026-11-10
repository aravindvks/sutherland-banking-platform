package bank.pbp.orchestrator.onboarding;

import bank.pbp.orchestrator.temporal.TaskQueues;
import bank.pbp.orchestrator.temporal.WorkerRegistration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The onboarding domain's workflows and activities on Temporal. */
@Configuration
public class OnboardingWorkers {

    @Bean
    WorkerRegistration onboardingWorkers(OnboardingActivitiesImpl onboardingActivities) {
        return new WorkerRegistration("onboarding", TaskQueues.ONBOARDING,
                List.of(OnboardingWorkflowImpl.class), List.of(onboardingActivities));
    }
}
