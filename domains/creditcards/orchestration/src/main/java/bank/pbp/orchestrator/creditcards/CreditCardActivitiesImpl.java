package bank.pbp.orchestrator.creditcards;

import static bank.pbp.orchestrator.temporal.ActivitySupport.call;
import static bank.pbp.orchestrator.temporal.ActivitySupport.withCorrelation;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.Application;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.CreditCardActivities;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.Decision;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.Opened;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.Repayment;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.StatementNotice;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.StatementStatus;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows.Withdrawal;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Metrics;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Activities for the credit card sagas. Calls only adapters and the mapping service; the credit
 * rules are the vendors' (decision engine: limit and APR; Thredd: open-to-buy, interest, minimum
 * payment). The bank's own choices are its tariff (late fee) and its card controls.
 */
@Component
public class CreditCardActivitiesImpl implements CreditCardActivities {

    private static final Logger log = LoggerFactory.getLogger(CreditCardActivitiesImpl.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher events;
    private final RestClient mapping;
    private final RestClient thredd;
    private final RestClient essence;
    private final RestClient credit;
    private final String repaymentName;
    private final String repaymentSortCode;
    private final String repaymentAccountNumber;
    private final String lateFee;
    private final String dailyLimit;
    private final Duration coolingOff;

    public CreditCardActivitiesImpl(JdbcClient jdbc, TransactionTemplate tx, EventPublisher events,
                                    @Qualifier("mappingClient") RestClient mapping,
                                    @Qualifier("threddClient") RestClient thredd,
                                    @Qualifier("essenceClient") RestClient essence,
                                    @Qualifier("creditClient") RestClient credit,
                                    @Value("${pbp.credit.repayment-account.name}") String repaymentName,
                                    @Value("${pbp.credit.repayment-account.sort-code}") String repaymentSortCode,
                                    @Value("${pbp.credit.repayment-account.account-number}") String repaymentAccountNumber,
                                    @Value("${pbp.credit.late-fee}") String lateFee,
                                    @Value("${pbp.credit.daily-limit}") String dailyLimit,
                                    @Value("${pbp.credit.cooling-off}") String coolingOff) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.mapping = mapping;
        this.thredd = thredd;
        this.essence = essence;
        this.credit = credit;
        this.repaymentName = repaymentName;
        this.repaymentSortCode = repaymentSortCode;
        this.repaymentAccountNumber = repaymentAccountNumber;
        this.lateFee = lateFee;
        this.dailyLimit = dailyLimit;
        this.coolingOff = Duration.parse(coolingOff);
    }

    // ---------------------------------------------------------------------------------- application

    @Override
    public String creditRecordApplication(Application a) {
        return withCorrelation(a.correlationId(), () -> {
            jdbc.sql("""
                            INSERT INTO credit_application (application_id, customer_id, correlation_id, status, name,
                                annual_income, employment_status, monthly_housing, monthly_commitments, requested_limit)
                            VALUES (:id, :customer, :correlation, 'RECEIVED', :name, :income, :employment, :housing,
                                    :commitments, :requested)
                            ON CONFLICT (application_id) DO NOTHING""")
                    .param("id", UUID.fromString(a.applicationId()))
                    .param("customer", UUID.fromString(a.customerId()))
                    .param("correlation", a.correlationId())
                    .param("name", a.name())
                    .param("income", new BigDecimal(a.annualIncome()))
                    .param("employment", a.employmentStatus())
                    .param("housing", new BigDecimal(a.monthlyHousing()))
                    .param("commitments", new BigDecimal(a.monthlyCommitments()))
                    .param("requested", new BigDecimal(a.requestedLimit()))
                    .update();
            saga(a, "RECEIVED", "RUNNING");
            JsonNode customer = call("mapping.customers.get", () -> mapping.get()
                    .uri("/v1/customers/{id}", a.customerId()).retrieve().body(JsonNode.class));
            if (!"ACTIVE".equals(customer.path("status").asText())) {
                return "CUSTOMER_NOT_ACTIVE";
            }
            Integer open = jdbc.sql("""
                            SELECT count(*) FROM credit_account
                            WHERE customer_id = :customer AND status IN ('ACTIVE','ARREARS')""")
                    .param("customer", UUID.fromString(a.customerId())).query(Integer.class).single();
            Integer pending = jdbc.sql("""
                            SELECT count(*) FROM credit_application
                            WHERE customer_id = :customer AND application_id <> :id AND status IN ('RECEIVED','REFERRED','OFFERED')""")
                    .param("customer", UUID.fromString(a.customerId())).param("id", UUID.fromString(a.applicationId()))
                    .query(Integer.class).single();
            if (open > 0) {
                return "ALREADY_HAS_CARD";
            }
            if (pending > 0) {
                return "APPLICATION_IN_PROGRESS";
            }
            publish(a, "creditcard.applied", Map.of("requestedLimit", a.requestedLimit()));
            return "ELIGIBLE";
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Decision creditDecide(Application a) {
        return withCorrelation(a.correlationId(), () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("applicationId", a.applicationId());
            body.put("customerId", a.customerId());
            body.put("name", a.name());
            body.put("product", "CREDIT_CARD");
            body.put("annualIncome", a.annualIncome());
            body.put("employmentStatus", a.employmentStatus());
            body.put("monthlyHousing", a.monthlyHousing());
            body.put("monthlyCommitments", a.monthlyCommitments());
            body.put("requestedLimit", a.requestedLimit());
            JsonNode d = call("credit.decisions.create", () -> credit.post().uri("/v1/decisions")
                    .header("Idempotency-Key", a.applicationId() + ":decision").body(body).retrieve().body(JsonNode.class));
            List<String> reasons = new ArrayList<>();
            d.path("reasons").forEach(r -> reasons.add(r.asText()));
            String outcome = d.path("outcome").asText("DECLINE");
            String limit = d.path("creditLimit").isNull() ? null : d.path("creditLimit").asText(null);
            String apr = d.path("purchaseApr").asText("24.9");
            jdbc.sql("""
                            UPDATE credit_application SET decision_id = :decision, bureau_ref = :bureau, outcome = :outcome,
                                   reasons = :reasons, offered_limit = :limit, purchase_apr = :apr, updated_at = now()
                            WHERE application_id = :id""")
                    .param("decision", d.path("decisionId").asText(null))
                    .param("bureau", d.path("bureauRef").asText(null))
                    .param("outcome", outcome)
                    .param("reasons", String.join(",", reasons))
                    .param("limit", limit == null ? null : new BigDecimal(limit))
                    .param("apr", new BigDecimal(apr))
                    .param("id", UUID.fromString(a.applicationId()))
                    .update();
            Metrics.counter("pbp.credit.applications.decided", "outcome", outcome).increment();
            log.info("creditcard.decision applicationId={} outcome={} limit={} reasons={}", a.applicationId(), outcome,
                    limit, reasons);
            return new Decision(outcome, limit, apr, reasons);
        });
    }

    @Override
    public void creditDeclined(Application a, String outcome, List<String> reasons, String reviewer) {
        withCorrelation(a.correlationId(), () -> {
            status(a, "DECLINED", reviewer, reasons == null ? null : String.join(",", reasons));
            saga(a, "DECLINED", "COMPLETED");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reasons", reasons);
            // The customer is told the decision and the bureau used (CONC 4.2, CCA s157).
            data.put("bureau", "Simulated credit bureau");
            publish(a, "creditcard.declined", data);
            return null;
        });
    }

    @Override
    public void creditReferred(Application a, Decision d) {
        withCorrelation(a.correlationId(), () -> {
            status(a, "REFERRED", null, null);
            saga(a, "REFERRED", "WAITING");
            publish(a, "creditcard.referred", Map.of("reasons", d.reasons()));
            return null;
        });
    }

    @Override
    public void creditOffered(Application a, String creditLimit, String purchaseApr, String reviewer) {
        withCorrelation(a.correlationId(), () -> {
            jdbc.sql("""
                            UPDATE credit_application SET status = 'OFFERED', offered_limit = :limit, purchase_apr = :apr,
                                   reviewer = :reviewer, updated_at = now()
                            WHERE application_id = :id""")
                    .param("limit", new BigDecimal(creditLimit))
                    .param("apr", new BigDecimal(purchaseApr))
                    .param("reviewer", reviewer)
                    .param("id", UUID.fromString(a.applicationId()))
                    .update();
            saga(a, "OFFERED", "WAITING");
            // Pre-contract summary (SECCI headline terms) shown before the customer accepts the agreement.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("creditLimit", creditLimit);
            data.put("purchaseApr", purchaseApr);
            data.put("representativeApr", purchaseApr);
            data.put("minimumPayment", "The greater of £5 or 1% of the balance plus interest and fees");
            data.put("lateFee", lateFee);
            data.put("withdrawalDays", coolingOff.toDays());
            publish(a, "creditcard.offered", data);
            return null;
        });
    }

    @Override
    public void creditExpired(Application a, String stage) {
        withCorrelation(a.correlationId(), () -> {
            status(a, "EXPIRED", null, stage + "_TIMEOUT");
            saga(a, "EXPIRED", "COMPLETED");
            publish(a, "creditcard.expired", Map.of("stage", stage));
            return null;
        });
    }

    @Override
    public Opened creditOpenAccount(Application a, String creditLimit, String purchaseApr) {
        return withCorrelation(a.correlationId(), () -> {
            Optional<Opened> done = jdbc.sql("""
                            SELECT account_id, card_token, card_last4 FROM credit_account WHERE application_id = :id""")
                    .param("id", UUID.fromString(a.applicationId()))
                    .query((rs, n) -> new Opened(rs.getString(1), rs.getString(2), rs.getString(3))).optional();
            if (done.isPresent()) {
                return done.get();
            }
            JsonNode account = call("thredd.credit.accounts.open", () -> thredd.post().uri("/v1/credit/accounts")
                    .header("Idempotency-Key", a.applicationId() + ":account")
                    .body(Map.of("customerRef", a.customerId(), "creditLimit", money(creditLimit),
                            "purchaseApr", purchaseApr))
                    .retrieve().body(JsonNode.class));
            String accountId = account.path("accountId").asText();
            String accountRefId = link(a.customerId(), "THREDD", "CREDIT_ACCOUNT", accountId, null,
                    Map.of("creditLimit", money(creditLimit), "purchaseApr", purchaseApr));
            JsonNode card = call("thredd.cards.issue", () -> thredd.post().uri("/v1/cards")
                    .header("Idempotency-Key", a.applicationId() + ":card")
                    .body(Map.of("accountRef", accountId, "embossName", a.name() == null ? "CARDHOLDER" : a.name(),
                            "product", "CREDIT"))
                    .retrieve().body(JsonNode.class));
            String token = card.path("token").asText();
            String last4 = card.path("last4").asText();
            link(a.customerId(), "THREDD", "CARD", token, accountRefId,
                    Map.of("last4", last4, "product", "CREDIT", "dailyLimit", dailyLimit));
            tx.executeWithoutResult(s -> {
                jdbc.sql("""
                                INSERT INTO credit_account (account_id, application_id, customer_id, card_token, card_last4,
                                    credit_limit, purchase_apr, status, cooling_off_until, correlation_id)
                                VALUES (:account, :app, :customer, :token, :last4, :limit, :apr, 'ACTIVE', :coolingOff,
                                        :correlation)
                                ON CONFLICT (account_id) DO NOTHING""")
                        .param("account", accountId)
                        .param("app", UUID.fromString(a.applicationId()))
                        .param("customer", UUID.fromString(a.customerId()))
                        .param("token", token)
                        .param("last4", last4)
                        .param("limit", new BigDecimal(creditLimit))
                        .param("apr", new BigDecimal(purchaseApr))
                        .param("coolingOff", OffsetDateTime.now().plus(coolingOff))
                        .param("correlation", a.correlationId())
                        .update();
                jdbc.sql("UPDATE credit_application SET status = 'OPEN', account_id = :account, updated_at = now() WHERE application_id = :id")
                        .param("account", accountId).param("id", UUID.fromString(a.applicationId())).update();
                saga(a, "OPEN", "COMPLETED");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("accountId", accountId);
                data.put("cardToken", token);
                data.put("last4", last4);
                data.put("creditLimit", money(creditLimit));
                data.put("purchaseApr", purchaseApr);
                publish(a, "creditcard.opened", data);
            });
            log.info("creditcard.opened applicationId={} accountId={} last4={}", a.applicationId(), accountId, last4);
            return new Opened(accountId, token, last4);
        });
    }

    // ---------------------------------------------------------------------------------- repayment

    @Override
    public String creditRecordRepayment(Repayment r) {
        return withCorrelation(r.correlationId(), () -> {
            jdbc.sql("""
                            INSERT INTO credit_repayment (repayment_id, account_id, customer_id, debit_account, amount,
                                                          status, correlation_id)
                            VALUES (:id, :account, :customer, :debit, :amount, 'INITIATED', :correlation)
                            ON CONFLICT (repayment_id) DO NOTHING""")
                    .param("id", UUID.fromString(r.repaymentId()))
                    .param("account", r.accountId())
                    .param("customer", UUID.fromString(r.customerId()))
                    .param("debit", r.debitAccount())
                    .param("amount", new BigDecimal(r.amount()))
                    .param("correlation", r.correlationId())
                    .update();
            Optional<String> owner = jdbc.sql("SELECT customer_id::text FROM credit_account WHERE account_id = :id")
                    .param("id", r.accountId()).query(String.class).optional();
            if (owner.isEmpty() || !owner.get().equals(r.customerId())) {
                return "NOT_YOUR_CARD";
            }
            JsonNode debitOwner = call("mapping.resolve", () -> mapping.get()
                    .uri(u -> u.path("/v1/resolve").queryParam("system", "ESSENCE").queryParam("type", "ACCOUNT")
                            .queryParam("id", r.debitAccount()).build())
                    .retrieve().body(JsonNode.class));
            if (!r.customerId().equals(debitOwner.path("customer").path("customerId").asText())) {
                return "NOT_YOUR_ACCOUNT";
            }
            return "OK";
        });
    }

    @Override
    public String creditDebitCurrentAccount(Repayment r) {
        return withCorrelation(r.correlationId(), () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("debitAccount", r.debitAccount());
            body.put("creditor", Map.of("name", repaymentName, "sortCode", repaymentSortCode,
                    "accountNumber", repaymentAccountNumber));
            body.put("amount", Map.of("value", r.amount(), "currency", "GBP"));
            body.put("reference", "Credit card " + r.accountId());
            body.put("idempotencyKey", r.repaymentId());
            JsonNode result = call("essence.transfers", () -> essence.post().uri("/v1/transfers").body(body)
                    .retrieve().body(JsonNode.class));
            String ref = result.path("postingRef").asText(null);
            // Essence has moved the money: from here on nothing may fail this activity, or the workflow would
            // treat a completed debit as failed. The reference is for traceability only.
            recordRef("essence_ref", ref, r.repaymentId());
            return ref;
        });
    }

    @Override
    public String creditPostRepayment(Repayment r) {
        return withCorrelation(r.correlationId(), () -> {
            JsonNode result = call("thredd.credit.repayments", () -> thredd.post()
                    .uri("/v1/credit/accounts/{id}/repayments", r.accountId())
                    .header("Idempotency-Key", r.repaymentId())
                    .body(Map.of("amount", money(r.amount()), "reference", "Repayment " + r.repaymentId()))
                    .retrieve().body(JsonNode.class));
            String ref = result.path("repaymentRef").asText(null);
            // Thredd has credited the card. A failure after this point (e.g. the database briefly refusing
            // connections) used to fail the activity, exhaust its retries and refund money Thredd had already
            // credited: the card went into credit with nothing taken. Never fail after the vendor said yes.
            recordRef("thredd_ref", ref, r.repaymentId());
            return ref;
        });
    }

    /** Best effort: stores a vendor reference on the repayment row; logs instead of failing. */
    private void recordRef(String column, String ref, String repaymentId) {
        String sql = "thredd_ref".equals(column)
                ? "UPDATE credit_repayment SET thredd_ref = :ref, updated_at = now() WHERE repayment_id = :id"
                : "UPDATE credit_repayment SET essence_ref = :ref, updated_at = now() WHERE repayment_id = :id";
        try {
            jdbc.sql(sql).param("ref", ref).param("id", UUID.fromString(repaymentId)).update();
        } catch (RuntimeException e) {
            log.warn("creditcard.repayment {}={} not recorded for {}: {}", column, ref, repaymentId, e.toString());
        }
    }

    @Override
    public void creditRefundRepayment(Repayment r) {
        withCorrelation(r.correlationId(), () -> {
            JsonNode owner = call("mapping.resolve", () -> mapping.get()
                    .uri(u -> u.path("/v1/resolve").queryParam("system", "ESSENCE").queryParam("type", "ACCOUNT")
                            .queryParam("id", r.debitAccount()).build())
                    .retrieve().body(JsonNode.class));
            JsonNode meta = owner.path("ref").path("metadata");
            if (meta.path("accountNumber").asText("").isBlank()) {
                // Accounts linked without their sort code and number (e.g. by hand) need a manual refund.
                throw ApplicationFailure.newNonRetryableFailure("No account number on record for " + r.debitAccount()
                        + "; refund " + r.repaymentId() + " by hand", "CARD-REFUND-001");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("debitAccount", "ACC-CARDS");
            body.put("creditor", Map.of("name", meta.path("name").asText("Customer"),
                    "sortCode", meta.path("sortCode").asText(repaymentSortCode),
                    "accountNumber", meta.path("accountNumber").asText("")));
            body.put("amount", Map.of("value", r.amount(), "currency", "GBP"));
            body.put("reference", "Refund " + r.repaymentId());
            body.put("idempotencyKey", r.repaymentId() + ":refund");
            call("essence.transfers", () -> essence.post().uri("/v1/transfers").body(body).retrieve().body(JsonNode.class));
            return null;
        });
    }

    @Override
    public void creditRepaymentStatus(Repayment r, String status, String reason) {
        withCorrelation(r.correlationId(), () -> {
            jdbc.sql("UPDATE credit_repayment SET status = :s, reason = :reason, updated_at = now() WHERE repayment_id = :id")
                    .param("s", status).param("reason", reason).param("id", UUID.fromString(r.repaymentId())).update();
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "REFUNDED".equals(status)) {
                Metrics.counter("pbp.credit.repayments", "status", status).increment();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("repaymentId", r.repaymentId());
                data.put("accountId", r.accountId());
                data.put("amount", Map.of("value", r.amount(), "currency", "GBP"));
                data.put("status", status);
                data.put("reason", reason);
                events.publish(Topics.CARD_EVENTS, EventEnvelope.of("creditcard.repayment_" + status.toLowerCase(),
                        "orchestrator", r.correlationId(), null, r.customerId(), data));
            }
            return null;
        });
    }

    // ---------------------------------------------------------------------------------- statement

    @Override
    public void creditRecordStatement(StatementNotice s) {
        withCorrelation(s.correlationId(), () -> {
            Optional<String> customer = customerOf(s.accountId());
            if (customer.isEmpty()) {
                throw ApplicationFailure.newNonRetryableFailure("No credit account " + s.accountId(), "CARD-CREDIT-404");
            }
            int rows = jdbc.sql("""
                            INSERT INTO credit_statement (statement_id, account_id, statement_date, balance, minimum_payment,
                                                          interest, due_date, due_at, status, correlation_id)
                            VALUES (:id, :account, :date, :balance, :minimum, :interest, :dueDate, :dueAt, 'ISSUED', :corr)
                            ON CONFLICT (statement_id) DO NOTHING""")
                    .param("id", s.statementId())
                    .param("account", s.accountId())
                    .param("date", LocalDate.parse(s.statementDate()))
                    .param("balance", new BigDecimal(s.balance()))
                    .param("minimum", new BigDecimal(s.minimumPayment()))
                    .param("interest", new BigDecimal(s.interest() == null ? "0" : s.interest()))
                    .param("dueDate", LocalDate.parse(s.dueDate()))
                    .param("dueAt", OffsetDateTime.parse(s.dueAt()))
                    .param("corr", s.correlationId())
                    .update();
            if (rows > 0) {
                statementEvent(s, customer.get(), "creditcard.statement_ready", Map.of());
            }
            return null;
        });
    }

    @Override
    public StatementStatus creditStatementStatus(StatementNotice s) {
        return withCorrelation(s.correlationId(), () -> {
            JsonNode st = call("thredd.credit.statements.get", () -> thredd.get()
                    .uri("/v1/credit/statements/{id}", s.statementId()).retrieve().body(JsonNode.class));
            return new StatementStatus(st.path("status").asText("MISSED"), st.path("repaid").asText("0.00"));
        });
    }

    @Override
    public void creditStatementPaid(StatementNotice s, StatementStatus status) {
        withCorrelation(s.correlationId(), () -> {
            String customer = customerOf(s.accountId()).orElseThrow();
            tx.executeWithoutResult(t -> {
                jdbc.sql("UPDATE credit_statement SET status = :s, settled_at = now() WHERE statement_id = :id AND status = 'ISSUED'")
                        .param("s", status.status()).param("id", s.statementId()).update();
                // Paying the latest statement brings an account back from arrears.
                jdbc.sql("UPDATE credit_account SET status = 'ACTIVE', updated_at = now() WHERE account_id = :id AND status = 'ARREARS'")
                        .param("id", s.accountId()).update();
                statementEvent(s, customer, "creditcard.statement_paid", Map.of("status", status.status(),
                        "repaid", status.repaid()));
            });
            Metrics.counter("pbp.credit.statements", "status", status.status()).increment();
            return null;
        });
    }

    @Override
    public String creditChargeLateFee(StatementNotice s) {
        return withCorrelation(s.correlationId(), () -> {
            call("thredd.credit.fees", () -> thredd.post().uri("/v1/credit/accounts/{id}/fees", s.accountId())
                    .header("Idempotency-Key", s.statementId() + ":late")
                    .body(Map.of("amount", money(lateFee), "type", "LATE_PAYMENT"))
                    .retrieve().body(JsonNode.class));
            return money(lateFee);
        });
    }

    @Override
    public void creditStatementMissed(StatementNotice s, String fee) {
        withCorrelation(s.correlationId(), () -> {
            String customer = customerOf(s.accountId()).orElseThrow();
            tx.executeWithoutResult(t -> {
                int moved = jdbc.sql("""
                                UPDATE credit_statement SET status = 'MISSED', late_fee = :fee, settled_at = now()
                                WHERE statement_id = :id AND status = 'ISSUED'""")
                        .param("fee", new BigDecimal(fee)).param("id", s.statementId()).update();
                if (moved == 0) {
                    return;
                }
                jdbc.sql("""
                                UPDATE credit_account SET status = 'ARREARS', arrears_count = arrears_count + 1, updated_at = now()
                                WHERE account_id = :id AND status IN ('ACTIVE','ARREARS')""")
                        .param("id", s.accountId()).update();
                statementEvent(s, customer, "creditcard.payment_missed", Map.of("lateFee", fee));
            });
            Metrics.counter("pbp.credit.statements", "status", "MISSED").increment();
            log.info("creditcard.payment_missed statementId={} accountId={} lateFee={}", s.statementId(), s.accountId(), fee);
            return null;
        });
    }

    // ---------------------------------------------------------------------------------- withdrawal

    @Override
    public String creditWithdraw(Withdrawal w) {
        return withCorrelation(w.correlationId(), () -> {
            Optional<Map<String, Object>> acc = jdbc.sql("""
                            SELECT customer_id::text AS customer, status, cooling_off_until > now() AS within, card_token AS token
                            FROM credit_account WHERE account_id = :id""")
                    .param("id", w.accountId()).query().listOfRows().stream().findFirst();
            String result;
            if (acc.isEmpty() || !w.customerId().equals(acc.get().get("customer"))) {
                result = "NOT_YOUR_CARD";
            } else if (!Boolean.TRUE.equals(acc.get().get("within"))) {
                result = "WITHDRAWAL_PERIOD_ENDED";
            } else if (!"ACTIVE".equals(acc.get().get("status"))) {
                result = "NOT_ACTIVE";
            } else {
                try {
                    call("thredd.credit.accounts.close", () -> thredd.post().uri("/v1/credit/accounts/{id}/close", w.accountId())
                            .header("Idempotency-Key", w.accountId() + ":withdraw").retrieve().body(JsonNode.class));
                    call("thredd.cards.status", () -> thredd.post().uri("/v1/cards/{token}/status", acc.get().get("token"))
                            .header("Idempotency-Key", w.accountId() + ":withdraw-card")
                            .body(Map.of("status", "CLOSED")).retrieve().body(JsonNode.class));
                    jdbc.sql("UPDATE credit_account SET status = 'WITHDRAWN', updated_at = now() WHERE account_id = :id")
                            .param("id", w.accountId()).update();
                    result = "WITHDRAWN";
                } catch (ApplicationFailure e) {
                    if (!"CARD-CREDIT-003".equals(e.getType())) {
                        throw e;
                    }
                    result = "BALANCE_OUTSTANDING";
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", w.accountId());
            data.put("result", result);
            events.publish(Topics.CARD_EVENTS, EventEnvelope.of("WITHDRAWN".equals(result) ? "creditcard.withdrawn"
                    : "creditcard.withdrawal_refused", "orchestrator", w.correlationId(), null, w.customerId(), data));
            return result;
        });
    }

    // ---------------------------------------------------------------------------------- helpers

    private Optional<String> customerOf(String accountId) {
        return jdbc.sql("SELECT customer_id::text FROM credit_account WHERE account_id = :id")
                .param("id", accountId).query(String.class).optional();
    }

    private void status(Application a, String status, String reviewer, String reasons) {
        jdbc.sql("""
                        UPDATE credit_application SET status = :s, reviewer = COALESCE(:reviewer, reviewer),
                               reasons = COALESCE(:reasons, reasons), updated_at = now()
                        WHERE application_id = :id""")
                .param("s", status).param("reviewer", reviewer).param("reasons", reasons)
                .param("id", UUID.fromString(a.applicationId())).update();
    }

    /** The saga row, so open applications show in the flow metrics and the Control Room. */
    private void saga(Application a, String step, String status) {
        jdbc.sql("""
                        INSERT INTO saga (saga_id, saga_type, customer_id, correlation_id, current_step, status)
                        VALUES (:id, 'CREDIT_APPLICATION', :customer, :correlation, :step, :status)
                        ON CONFLICT (saga_id) DO UPDATE SET current_step = EXCLUDED.current_step,
                               status = EXCLUDED.status, updated_at = now()""")
                .param("id", UUID.fromString(a.applicationId()))
                .param("customer", UUID.fromString(a.customerId()))
                .param("correlation", a.correlationId())
                .param("step", step)
                .param("status", status)
                .update();
    }

    private void publish(Application a, String type, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", a.applicationId());
        data.putAll(extra);
        events.publish(Topics.CARD_EVENTS, EventEnvelope.of(type, "orchestrator", a.correlationId(), null,
                a.customerId(), data));
    }

    private void statementEvent(StatementNotice s, String customerId, String type, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("statementId", s.statementId());
        data.put("accountId", s.accountId());
        data.put("balance", s.balance());
        data.put("minimumPayment", s.minimumPayment());
        data.put("dueDate", s.dueDate());
        data.putAll(extra);
        events.publish(Topics.CARD_EVENTS, EventEnvelope.of(type, "orchestrator", s.correlationId(), null, customerId, data));
    }

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

    private static String money(String amount) {
        return new BigDecimal(amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
