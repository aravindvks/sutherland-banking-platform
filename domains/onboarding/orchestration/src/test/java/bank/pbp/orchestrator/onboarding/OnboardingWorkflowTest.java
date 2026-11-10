package bank.pbp.orchestrator.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.orchestrator.onboarding.OnboardingTypes.AccountDetails;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.CardDetails;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingCommand;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingStatus;
import bank.pbp.orchestrator.temporal.TaskQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the onboarding saga on Temporal's in-memory test server: the debit card step follows the cards module. */
class OnboardingWorkflowTest {

    private TestWorkflowEnvironment env;
    private FakeActivities fake;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        fake = new FakeActivities();
        Worker worker = env.newWorker(TaskQueues.ONBOARDING);
        worker.registerWorkflowImplementationTypes(OnboardingWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void bankWithDebitCardsIssuesACard() {
        OnboardingStatus s = run(Boolean.TRUE);
        assertThat(s.status()).isEqualTo("COMPLETED");
        assertThat(s.cardLast4()).isEqualTo("4242");
        assertThat(fake.calls).contains("issueCard");
        assertThat(fake.steps).containsSubsequence("ACCOUNT_OPENED", "CARD_ISSUED");
    }

    @Test
    void bankWithoutDebitCardsCompletesWithoutACard() {
        OnboardingStatus s = run(Boolean.FALSE);
        assertThat(s.status()).isEqualTo("COMPLETED");
        assertThat(s.accountId()).isEqualTo("ACC-1");
        assertThat(s.cardLast4()).isNull();
        assertThat(fake.calls).doesNotContain("issueCard");
        assertThat(fake.steps).doesNotContain("CARD_ISSUED");
    }

    @Test
    void commandFromBeforeTheSwitchStillIssuesACard() {
        OnboardingStatus s = run(null);
        assertThat(s.status()).isEqualTo("COMPLETED");
        assertThat(fake.calls).contains("issueCard");
    }

    private OnboardingStatus run(Boolean issueDebitCard) {
        String id = "0192f1c5-0000-7000-8000-00000000000" + (issueDebitCard == null ? "0" : issueDebitCard ? "1" : "2");
        OnboardingWorkflow wf = env.getWorkflowClient().newWorkflowStub(OnboardingWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TaskQueues.ONBOARDING).setWorkflowId("onboarding-" + id).build());
        OnboardingCommand cmd = new OnboardingCommand(id, "COR-ONB-TEST", "INDIVIDUAL", "Test Person",
                "test@example.com", issueDebitCard);
        CompletableFuture<OnboardingStatus> result = WorkflowClient.execute(wf::run, cmd);
        env.sleep(Duration.ofSeconds(1));
        wf.kycOutcome("approved", "LOW");
        return result.join();
    }

    static final class FakeActivities implements OnboardingActivities {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> steps = Collections.synchronizedList(new CopyOnWriteArrayList<>());

        @Override
        public String createCustomer(OnboardingCommand command) {
            calls.add("createCustomer");
            return "CUST-1";
        }

        @Override
        public String openKycCase(OnboardingCommand command, String customerId) {
            calls.add("openKycCase");
            return "CASE-1";
        }

        @Override
        public void recordStep(OnboardingCommand command, String step, String sagaStatus) {
            steps.add(step);
        }

        @Override
        public void applyKyc(OnboardingCommand command, String customerId, String riskRating) {
            calls.add("applyKyc");
        }

        @Override
        public AccountDetails openAccount(OnboardingCommand command, String customerId) {
            calls.add("openAccount");
            return new AccountDetails("ACC-1", "200001", "12345678", "REF-1");
        }

        @Override
        public CardDetails issueCard(OnboardingCommand command, String customerId, AccountDetails account) {
            calls.add("issueCard");
            return new CardDetails("thr_tok_test", "4242");
        }

        @Override
        public void setCustomerStatus(OnboardingCommand command, String customerId, String status, String reason) {
            calls.add("setCustomerStatus:" + status);
        }

        @Override
        public void finish(OnboardingCommand command, OnboardingStatus status) {
            calls.add("finish");
        }
    }
}
