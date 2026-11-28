package bank.pbp.orchestrator.payments;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.MarketGuard;
import bank.pbp.market.uk.UkMarketPack;
import bank.pbp.market.us.UsAbaScheme;
import bank.pbp.market.us.UsMarketPack;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The payment saga in a deployment built with the US pack (upcoming): no payee name check, wires wait for the bank's
 * hours, ACH and instant payments go straight out. Same fake activities as PaymentWorkflowTest.
 */
class OtherMarketWorkflowTest {

    private TestWorkflowEnvironment env;
    private PaymentWorkflowTest.FakeActivities fake;

    @BeforeEach
    void setUp() {
        MarketGuard.check("us", new UsMarketPack(new BigDecimal("25000.00"), new BigDecimal("10000.00"),
                new BigDecimal("25000.00"), new BigDecimal("1000000.00"), new BigDecimal("25.00"), LocalTime.of(9, 0),
                LocalTime.of(17, 0), Set.of(), new BigDecimal("5000.00"), Set.of(), Set.of()));
        env = TestWorkflowEnvironment.newInstance();
        fake = new PaymentWorkflowTest.FakeActivities();
        Worker worker = env.newWorker(TaskQueues.PAYMENTS);
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
        // Back to the UK for the other tests in this JVM.
        MarketGuard.check("uk", new UkMarketPack(new BigDecimal("25000.00"), new BigDecimal("10000.00"),
                new BigDecimal("1000000.00"), new BigDecimal("20.00"), LocalTime.of(6, 0), LocalTime.of(17, 30),
                Set.of(), new BigDecimal("5000.00")));
    }

    @Test
    void instantPaymentSkipsTheNameCheck() {
        assertThat(run(command("100.00", "RTP"))).isEqualTo("COMPLETED");
        assertThat(fake.copCalls.get()).isZero();
        assertThat(fake.reasons.get(0)).isEqualTo("No payee name check in this market");
        assertThat(fake.windowsAsked).isEmpty();
        assertThat(fake.reasons).contains("Settled by the scheme");
    }

    @Test
    void wireOutsideHoursIsQueuedThenSent() {
        fake.chapsOpen = false;   // the fake's one window, closed
        assertThat(run(command("5000.00", "WIRE"))).isEqualTo("COMPLETED");
        assertThat(fake.windowsAsked).containsExactly("WIRE");
        assertThat(fake.transitions).containsExactly("INITIATED->CHECKS_PASSED", "CHECKS_PASSED->QUEUED",
                "QUEUED->SUBMITTED", "SUBMITTED->ACCEPTED", "ACCEPTED->COMPLETED");
        assertThat(fake.reasons).contains("Wire transfer is closed; the payment will be sent when it opens at 2026-11-09T06:00Z",
                "Settled by Wire transfer");
    }

    @Test
    void achKeepsNoHoursSoIsNotQueued() {
        fake.chapsOpen = false;
        assertThat(run(command("100.00", "ACH"))).isEqualTo("COMPLETED");
        assertThat(fake.windowsAsked).isEmpty();
        assertThat(fake.reasons).contains("Settled by ACH transfer");
    }

    private String run(PaymentCommand cmd) {
        PaymentWorkflow workflow = env.getWorkflowClient().newWorkflowStub(PaymentWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TaskQueues.PAYMENTS)
                        .setWorkflowId("payment-" + cmd.paymentId()).build());
        WorkflowClient.start(workflow::run, cmd);
        workflow.paymentStatus("ACCEPTED", null);
        workflow.paymentStatus("COMPLETED", null);
        return WorkflowStub.fromTyped(workflow).getResult(String.class);
    }

    private static PaymentCommand command(String amount, String type) {
        return new PaymentCommand("0192f1c5-0000-7000-8000-0000000000a1", "0192f1c4-0000-7000-8000-000000000001",
                "COR-PAY-7K2M9QXA4B1D", "ACC-5501", "Pat Lee", null, null, amount, "USD", "Rent", type, "key-1",
                "2026-11-06T10:00:00Z", null, null, UsAbaScheme.of("021000021", "000123456789", "CHECKING"));
    }
}
