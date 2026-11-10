package bank.pbp.orchestrator.compliance;

import bank.pbp.orchestrator.temporal.TaskQueues;
import bank.pbp.orchestrator.temporal.WorkerRegistration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The compliance domain's workflows and activities on Temporal. */
@Configuration
public class ComplianceWorkers {

    @Bean
    WorkerRegistration complianceWorkerRegistration(ComplianceActivitiesImpl complianceActivities) {
        return new WorkerRegistration("compliance", TaskQueues.COMPLIANCE,
                List.of(ComplianceWorkflow.Impl.class), List.of(complianceActivities));
    }
}
