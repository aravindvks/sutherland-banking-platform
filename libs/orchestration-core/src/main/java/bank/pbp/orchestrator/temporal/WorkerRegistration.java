package bank.pbp.orchestrator.temporal;

import java.util.List;

/**
 * What a domain module runs on Temporal: its workflow types and activity implementations, on one task queue.
 * Each domain declares one or more of these as Spring beans; the orchestrator starts one worker per task queue
 * with everything registered for it. A domain module that is not in the build registers nothing.
 */
public record WorkerRegistration(String domain, String taskQueue, List<Class<?>> workflowTypes, List<Object> activities) {

    public WorkerRegistration {
        workflowTypes = List.copyOf(workflowTypes);
        activities = List.copyOf(activities);
    }
}
