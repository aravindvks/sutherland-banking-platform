package bank.pbp.orchestrator.onboarding;

import static bank.pbp.orchestrator.temporal.ActivitySupport.call;
import static bank.pbp.orchestrator.temporal.ActivitySupport.withCorrelation;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.AccountDetails;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.CardDetails;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingCommand;
import bank.pbp.orchestrator.onboarding.OnboardingTypes.OnboardingStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OnboardingActivitiesImpl implements OnboardingActivities {

    private static final Logger log = LoggerFactory.getLogger(OnboardingActivitiesImpl.class);

    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final RestClient mapping;
    private final RestClient atfinity;
    private final RestClient essence;
    private final RestClient thredd;

    public OnboardingActivitiesImpl(JdbcClient jdbc, EventPublisher events,
                                    @Qualifier("mappingClient") RestClient mapping,
                                    @Qualifier("atfinityClient") RestClient atfinity,
                                    @Qualifier("essenceClient") RestClient essence,
                                    @Qualifier("threddClient") RestClient thredd) {
        this.jdbc = jdbc;
        this.events = events;
        this.mapping = mapping;
        this.atfinity = atfinity;
        this.essence = essence;
        this.thredd = thredd;
    }

    @Override
    public String createCustomer(OnboardingCommand c) {
        return withCorrelation(c.correlationId(), () -> {
            UUID sagaId = UUID.fromString(c.onboardingId());
            Optional<String> existing = jdbc.sql("SELECT customer_id::text FROM saga WHERE saga_id = :id")
                    .param("id", sagaId).query(String.class).optional();
            if (existing.isPresent()) {
                return existing.get();
            }
            JsonNode customer = call("mapping.customers.create", () -> mapping.post().uri("/v1/customers")
                    .body(Map.of("customerType", c.customerType())).retrieve().body(JsonNode.class));
            String customerId = customer.path("customerId").asText();
            jdbc.sql("""
                            INSERT INTO saga (saga_id, saga_type, customer_id, correlation_id, current_step, status)
                            VALUES (:id, 'ONBOARDING', :customerId, :correlationId, 'CUSTOMER_CREATED', 'RUNNING')
                            ON CONFLICT DO NOTHING""")
                    .param("id", sagaId)
                    .param("customerId", UUID.fromString(customerId))
                    .param("correlationId", c.correlationId())
                    .update();
            log.info("onboarding.customer_created onboardingId={} customerId={}", c.onboardingId(), customerId);
            return customerId;
        });
    }

    @Override
    public String openKycCase(OnboardingCommand c, String customerId) {
        return withCorrelation(c.correlationId(), () -> {
            String workflow = "BUSINESS".equals(c.customerType()) ? "BUSINESS_KYB_STANDARD" : "RETAIL_KYC_STANDARD";
            JsonNode kycCase = call("atfinity.cases.create", () -> atfinity.post().uri("/v1/kyc/cases")
                    .body(Map.of("customerId", customerId, "subjectName", c.name(), "workflowCode", workflow))
                    .retrieve().body(JsonNode.class));
            String caseId = kycCase.path("caseId").asText();
            link(customerId, "ATFINITY", "KYC_CASE", caseId, null, Map.of("workflowCode", workflow));
            return caseId;
        });
    }

    @Override
    public void recordStep(OnboardingCommand c, String step, String sagaStatus) {
        withCorrelation(c.correlationId(), () -> jdbc.sql("""
                        UPDATE saga SET current_step = :step, status = :status, updated_at = now()
                        WHERE saga_id = :id""")
                .param("step", step).param("status", sagaStatus).param("id", UUID.fromString(c.onboardingId()))
                .update());
    }

    @Override
    public void applyKyc(OnboardingCommand c, String customerId, String riskRating) {
        withCorrelation(c.correlationId(), () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("kycStatus", "VALID");
            body.put("validUntil", LocalDate.now(ZoneOffset.UTC).plusYears(1).toString());
            body.put("riskRating", riskRating);
            return call("mapping.customers.kyc", () -> mapping.patch().uri("/v1/customers/{id}/kyc", customerId)
                    .body(body).retrieve().toBodilessEntity());
        });
    }

    @Override
    public AccountDetails openAccount(OnboardingCommand c, String customerId) {
        return withCorrelation(c.correlationId(), () -> {
            String partyType = "BUSINESS".equals(c.customerType()) ? "BUSINESS" : "INDIVIDUAL";
            JsonNode party = call("essence.parties.create", () -> essence.post().uri("/v1/parties")
                    .header("Idempotency-Key", c.onboardingId() + ":party")
                    .body(Map.of("name", c.name(), "partyType", partyType))
                    .retrieve().body(JsonNode.class));
            String partyId = party.path("partyId").asText();
            String partyRef = link(customerId, "ESSENCE", "PARTY", partyId, null, null);

            JsonNode acc = call("essence.accounts.open", () -> essence.post().uri("/v1/accounts")
                    .header("Idempotency-Key", c.onboardingId() + ":account")
                    .body(Map.of("partyId", partyId, "name", c.name()))
                    .retrieve().body(JsonNode.class));
            String accountId = acc.path("accountId").asText();
            String sortCode = acc.path("sortCode").asText();
            String accountNumber = acc.path("accountNumber").asText();
            String accountRef = link(customerId, "ESSENCE", "ACCOUNT", accountId, partyRef,
                    Map.of("sortCode", sortCode, "accountNumber", accountNumber, "name", c.name()));
            log.info("onboarding.account_opened customerId={} accountId={}", customerId, accountId);
            return new AccountDetails(accountId, sortCode, accountNumber, accountRef);
        });
    }

    @Override
    public CardDetails issueCard(OnboardingCommand c, String customerId, AccountDetails account) {
        return withCorrelation(c.correlationId(), () -> {
            JsonNode card = call("thredd.cards.issue", () -> thredd.post().uri("/v1/cards")
                    .header("Idempotency-Key", c.onboardingId() + ":card")
                    .body(Map.of("accountRef", account.accountId(), "embossName", c.name()))
                    .retrieve().body(JsonNode.class));
            String token = card.path("token").asText();
            String last4 = card.path("last4").asText();
            link(customerId, "THREDD", "CARD", token, account.accountRefId(), Map.of("last4", last4));
            log.info("onboarding.card_issued customerId={} last4={}", customerId, last4);
            return new CardDetails(token, last4);
        });
    }

    @Override
    public void setCustomerStatus(OnboardingCommand c, String customerId, String status, String reason) {
        withCorrelation(c.correlationId(), () -> {
            JsonNode current = call("mapping.customers.get", () -> mapping.get().uri("/v1/customers/{id}", customerId)
                    .retrieve().body(JsonNode.class));
            if (status.equals(current.path("status").asText())) {
                return null;
            }
            return call("mapping.customers.status", () -> mapping.patch().uri("/v1/customers/{id}/status", customerId)
                    .body(Map.of("status", status, "reason", reason)).retrieve().toBodilessEntity());
        });
    }

    @Override
    public void finish(OnboardingCommand c, OnboardingStatus s) {
        withCorrelation(c.correlationId(), () -> {
            String sagaStatus = "FAILED".equals(s.status()) ? "FAILED" : "COMPLETED";
            jdbc.sql("""
                            UPDATE saga SET current_step = :step, status = :status, updated_at = now()
                            WHERE saga_id = :id""")
                    .param("step", s.step()).param("status", sagaStatus).param("id", UUID.fromString(c.onboardingId()))
                    .update();
            String type = switch (s.status()) {
                case "COMPLETED" -> "onboarding.completed";
                case "REJECTED" -> "onboarding.rejected";
                default -> "onboarding.failed";
            };
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("onboardingId", s.onboardingId());
            data.put("status", s.status());
            data.put("customerId", s.customerId());
            data.put("accountId", s.accountId());
            data.put("sortCode", s.sortCode());
            data.put("accountNumber", s.accountNumber());
            data.put("cardLast4", s.cardLast4());
            data.put("reason", s.reason());
            events.publish(Topics.ONBOARDING_EVENTS,
                    EventEnvelope.of(type, "orchestrator", c.correlationId(), null, s.customerId(), data));
            log.info("{} onboardingId={} customerId={}", type, s.onboardingId(), s.customerId());
            return null;
        });
    }

    /** Links a vendor ID to the customer (idempotent in the mapping service). Returns the ref ID. */
    private String link(String customerId, String system, String entityType, String externalId, String parentRefId,
                        Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system", system);
        body.put("entityType", entityType);
        body.put("externalId", externalId);
        body.put("parentRefId", parentRefId);
        body.put("metadata", metadata);
        JsonNode ref = call("mapping.refs.link", () -> mapping.post().uri("/v1/customers/{id}/refs", customerId)
                .body(body).retrieve().body(JsonNode.class));
        return ref.path("refId").asText();
    }
}
