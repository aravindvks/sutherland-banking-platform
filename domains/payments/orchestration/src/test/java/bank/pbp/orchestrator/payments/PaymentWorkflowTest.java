package bank.pbp.orchestrator.payments;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the payment saga on Temporal's in-memory test server with fake activities. */
class PaymentWorkflowTest {

    private TestWorkflowEnvironment env;
    private FakeActivities fake;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        fake = new FakeActivities();
        Worker worker = env.newWorker(TaskQueues.PAYMENTS);
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void sameBankPaymentCompletes() {
        assertThat(run(command("500.00", "INTERNAL", "MATCH"))).isEqualTo("COMPLETED");
        assertThat(fake.transitions).containsExactly(
                "INITIATED->CHECKS_PASSED", "CHECKS_PASSED->SUBMITTED", "SUBMITTED->COMPLETED");
    }

    @Test
    void insufficientFundsIsRejected() {
        fake.refuseTransfer = true;
        assertThat(run(command("500.00", "INTERNAL", "MATCH"))).isEqualTo("REJECTED");
        assertThat(fake.transitions).last().isEqualTo("SUBMITTED->REJECTED");
    }

    @Test
    void overLimitIsRejectedBeforeSubmission() {
        assertThat(run(command("30000.00", "INTERNAL", "MATCH"))).isEqualTo("REJECTED");
        assertThat(fake.transitions).containsExactly("INITIATED->REJECTED");
    }

    @Test
    void nameThatDoesNotMatchIsRejected() {
        fake.cop = new PaymentActivities.CopCheck("NO_MATCH", null, "ANNM");
        assertThat(run(command("20.00", "FPS", null))).isEqualTo("REJECTED");
        assertThat(fake.transitions).containsExactly("INITIATED->REJECTED");
        assertThat(lastReason()).startsWith("PAY-COP-001");
    }

    @Test
    void closeMatchNeedsTheCustomerToConfirm() {
        fake.cop = new PaymentActivities.CopCheck("CLOSE_MATCH", "Ravi Kumar", "MBAM");
        assertThat(run(command("20.00", "INTERNAL", null))).isEqualTo("REJECTED");
        assertThat(lastReason()).contains("Ravi Kumar");

        fake.transitions.clear();
        PaymentCommand confirmed = command("20.00", "INTERNAL", "CLOSE_MATCH", "0192f1c5-0000-7000-8000-000000000002");
        assertThat(run(confirmed)).isEqualTo("COMPLETED");
    }

    @Test
    void confirmationOfPayeeUnavailableGoesAhead() {
        fake.copFails = true;
        assertThat(run(command("20.00", "INTERNAL", null))).isEqualTo("COMPLETED");
        assertThat(fake.reasons.get(0)).contains("UNAVAILABLE");
    }

    @Test
    void highValuePaymentWaitsForStaffApproval() {
        PaymentCommand cmd = command("12000.00", "INTERNAL", "MATCH");
        PaymentWorkflow workflow = stub(cmd);
        WorkflowClient.start(workflow::run, cmd);
        waitForStatus(workflow, "HELD");
        workflow.reviewDecision("APPROVE", "aravind", "Checked with the customer");
        assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class)).isEqualTo("COMPLETED");
        assertThat(fake.transitions).containsExactly("INITIATED->CHECKS_PASSED", "CHECKS_PASSED->HELD",
                "HELD->SUBMITTED", "SUBMITTED->COMPLETED");
    }

    @Test
    void highValuePaymentRejectedByStaff() {
        PaymentCommand cmd = command("12000.00", "FPS", "MATCH");
        PaymentWorkflow workflow = stub(cmd);
        WorkflowClient.start(workflow::run, cmd);
        waitForStatus(workflow, "HELD");
        workflow.reviewDecision("REJECT", "aravind", "Customer did not recognise it");
        assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class)).isEqualTo("REJECTED");
        assertThat(fake.transitions).last().isEqualTo("HELD->REJECTED");
    }

    @Test
    void chapsOutsideHoursIsQueuedThenSentAndTheFeeCharged() {
        fake.chapsOpen = false;
        PaymentCommand cmd = command("5000.00", "CHAPS", "MATCH");
        PaymentWorkflow workflow = stub(cmd);
        WorkflowClient.start(workflow::run, cmd);
        // Scheme updates sent now wait in the saga until it has submitted the payment.
        workflow.paymentStatus("ACCEPTED", null);
        workflow.paymentStatus("COMPLETED", null);
        assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class)).isEqualTo("COMPLETED");
        assertThat(fake.transitions).containsExactly("INITIATED->CHECKS_PASSED", "CHECKS_PASSED->QUEUED",
                "QUEUED->SUBMITTED", "SUBMITTED->ACCEPTED", "ACCEPTED->COMPLETED");
        assertThat(fake.feesCharged).containsExactly("20.00");
    }

    @Test
    void otherBankPaymentCompletesWhenTheSchemeReportsCompleted() {
        PaymentCommand cmd = command("20.00", "FPS", "MATCH");
        PaymentWorkflow workflow = stub(cmd);
        WorkflowClient.start(workflow::run, cmd);
        workflow.paymentStatus("ACCEPTED", null);
        workflow.paymentStatus("COMPLETED", null);
        assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class)).isEqualTo("COMPLETED");
        assertThat(fake.transitions).containsExactly("INITIATED->CHECKS_PASSED", "CHECKS_PASSED->SUBMITTED",
                "SUBMITTED->ACCEPTED", "ACCEPTED->COMPLETED");
        assertThat(fake.feesCharged).isEmpty();
    }

    @Test
    void otherBankPaymentRejectedByTheScheme() {
        PaymentCommand cmd = command("20.00", "FPS", "MATCH");
        PaymentWorkflow workflow = stub(cmd);
        WorkflowClient.start(workflow::run, cmd);
        workflow.paymentStatus("REJECTED", "AC01 Incorrect account number");
        assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class)).isEqualTo("REJECTED");
    }

    @Test
    void otherBankPaymentFailsWhenNoFinalStatusArrives() {
        assertThat(run(command("20.00", "FPS", "MATCH"))).isEqualTo("FAILED");
        assertThat(fake.transitions).last().isEqualTo("SUBMITTED->FAILED");
    }

    private String run(PaymentCommand command) {
        return stub(command).run(command);
    }

    private void waitForStatus(PaymentWorkflow workflow, String status) {
        for (int i = 0; i < 200 && !status.equals(workflow.currentStatus()); i++) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(workflow.currentStatus()).isEqualTo(status);
    }

    private String lastReason() {
        return fake.reasons.get(fake.reasons.size() - 1);
    }

    private PaymentWorkflow stub(PaymentCommand command) {
        return env.getWorkflowClient().newWorkflowStub(PaymentWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TaskQueues.PAYMENTS)
                        .setWorkflowId("payment-" + command.paymentId()).build());
    }

    private static PaymentCommand command(String amount, String type, String copResult) {
        return command(amount, type, copResult, "0192f1c5-0000-7000-8000-000000000001");
    }

    private static PaymentCommand command(String amount, String type, String copResult, String paymentId) {
        return new PaymentCommand(paymentId, "0192f1c4-0000-7000-8000-000000000001",
                "COR-PAY-7K2M9QXA4B1D", "ACC-5501", "Ravi Kumar", "200001", "41550198", amount, "GBP",
                "Rent share", type, "key-1", "2026-11-06T10:00:00Z", copResult, null);
    }

    /** Stands in for the database, Kafka and Essence adapter. */
    static class FakeActivities implements PaymentActivities {

        final List<String> transitions = Collections.synchronizedList(new ArrayList<>());
        final List<String> reasons = Collections.synchronizedList(new ArrayList<>());
        final List<String> feesCharged = Collections.synchronizedList(new ArrayList<>());
        volatile boolean refuseTransfer;
        volatile boolean copFails;
        volatile boolean chapsOpen = true;
        volatile CopCheck cop = new CopCheck("MATCH", null, null);

        @Override
        public void createPayment(PaymentCommand command) {
        }

        @Override
        public RiskResult riskCheck(PaymentCommand command) {
            BigDecimal amount = new BigDecimal(command.amount());
            if (amount.compareTo(new BigDecimal("25000.00")) > 0) {
                return RiskResult.fail("PAY-LIMIT-001", "over limit");
            }
            return RiskResult.pass(amount.compareTo(new BigDecimal("10000.00")) >= 0,
                    "CHAPS".equals(command.paymentType()) ? "20.00" : "0.00");
        }

        @Override
        public CopCheck confirmPayee(PaymentCommand command) {
            if (copFails) {
                throw ApplicationFailure.newFailure("CoP down", "PAY-COP-002");
            }
            return cop;
        }

        @Override
        public Window chapsWindow() {
            return chapsOpen ? new Window(true, "now", 0) : new Window(false, "2026-11-09T06:00Z", 3_600_000);
        }

        @Override
        public void transition(PaymentCommand command, String from, String to, String reason) {
            transitions.add(from + "->" + to);
            reasons.add(String.valueOf(reason));
        }

        @Override
        public String postInternalTransfer(PaymentCommand command) {
            if (refuseTransfer) {
                throw ApplicationFailure.newNonRetryableFailure("Available balance is below the amount", "PAY-FUNDS-001");
            }
            return "PST-TEST0001";
        }

        @Override
        public String submitPayment(PaymentCommand command) {
            return "FIN-TEST0001";
        }

        @Override
        public String chargeFee(PaymentCommand command, String fee) {
            feesCharged.add(fee);
            return "PST-FEE0001";
        }
    }
}
