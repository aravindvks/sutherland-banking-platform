package bank.pbp.orchestrator.payments;

import bank.pbp.orchestrator.directdebits.DirectDebitActivitiesImpl;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows;
import bank.pbp.orchestrator.schedules.ScheduleActivitiesImpl;
import bank.pbp.orchestrator.schedules.ScheduleWorkflow;
import bank.pbp.orchestrator.temporal.TaskQueues;
import bank.pbp.orchestrator.temporal.WorkerRegistration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The accounts and payments domain on Temporal: payments, inbound payments, schedules and Direct Debits. */
@Configuration
public class PaymentsWorkers {

    @Bean
    WorkerRegistration paymentsWorkerRegistration(PaymentActivitiesImpl paymentActivities, InboundActivitiesImpl inboundActivities,
                                       ScheduleActivitiesImpl scheduleActivities,
                                       DirectDebitActivitiesImpl directDebitActivities) {
        return new WorkerRegistration("payments", TaskQueues.PAYMENTS,
                List.of(PaymentWorkflowImpl.class, InboundPaymentWorkflow.Impl.class, ScheduleWorkflow.Impl.class,
                        DirectDebitWorkflows.MandateImpl.class, DirectDebitWorkflows.CollectionImpl.class,
                        DirectDebitWorkflows.ClaimImpl.class),
                List.of(paymentActivities, inboundActivities, scheduleActivities, directDebitActivities));
    }
}
