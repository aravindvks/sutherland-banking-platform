package bank.pbp.orchestrator.temporal;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal client and workers. Namespace pbp-&lt;env&gt;; one worker per task queue. */
@Configuration
public class TemporalConfig {

    /**
     * Temporal SDK metrics (task latency, failures, polls) on the application's Micrometer registry, so
     * Prometheus scrapes them with everything else (LLD section 15).
     */
    @Bean
    WorkflowServiceStubs workflowServiceStubs(@Value("${temporal.target}") String target, MeterRegistry registry) {
        Scope metrics = new RootScopeBuilder()
                .reporter(new MicrometerClientStatsReporter(registry))
                .reportEvery(com.uber.m3.util.Duration.ofSeconds(10));
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).setMetricsScope(metrics).build());
    }

    /**
     * OpenTracing interceptors over an OpenTelemetry shim: the Kafka listener's span carries into the
     * workflow it starts, and each activity's HTTP calls hang off the activity span.
     */
    @Bean
    OpenTracingOptions temporalTracing(ObjectProvider<OpenTelemetry> openTelemetry) {
        OpenTelemetry otel = openTelemetry.getIfAvailable(OpenTelemetry::noop);
        return OpenTracingOptions.newBuilder().setTracer(OpenTracingShim.createTracerShim(otel)).build();
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, @Value("${temporal.namespace}") String namespace,
                                  OpenTracingOptions tracing) {
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace)
                .setInterceptors(new OpenTracingClientInterceptor(tracing)).build());
    }

    @Bean
    WorkerFactory workerFactory(WorkflowClient client, OpenTracingOptions tracing) {
        return WorkerFactory.newInstance(client, WorkerFactoryOptions.newBuilder()
                .setWorkerInterceptors(new OpenTracingWorkerInterceptor(tracing)).build());
    }

    /** One worker per task queue, with every domain's registrations for that queue (see {@link WorkerRegistration}). */
    @Bean
    TemporalWorkers temporalWorkers(WorkerFactory factory, List<WorkerRegistration> registrations) {
        return new TemporalWorkers(factory, registrations);
    }

    /** Starts polling after the context is ready and stops cleanly on shutdown. */
    static final class TemporalWorkers implements SmartLifecycle {

        private static final Logger log = LoggerFactory.getLogger(TemporalWorkers.class);

        private final WorkerFactory factory;
        private final List<WorkerRegistration> registrations;
        private volatile boolean running;

        TemporalWorkers(WorkerFactory factory, List<WorkerRegistration> registrations) {
            this.factory = factory;
            this.registrations = List.copyOf(registrations);
        }

        @Override
        public void start() {
            Map<String, Worker> workers = new LinkedHashMap<>();
            for (WorkerRegistration r : registrations) {
                Worker worker = workers.computeIfAbsent(r.taskQueue(), factory::newWorker);
                worker.registerWorkflowImplementationTypes(r.workflowTypes().toArray(Class<?>[]::new));
                worker.registerActivitiesImplementations(r.activities().toArray());
            }
            factory.start();
            running = true;
            log.info("temporal.workers.started queues={} domains={}", workers.keySet(),
                    registrations.stream().map(WorkerRegistration::domain).toList());
        }

        @Override
        public void stop() {
            factory.shutdown();
            factory.awaitTermination(10, TimeUnit.SECONDS);
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
