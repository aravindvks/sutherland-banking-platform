package bank.pbp.simulator.essence;

import bank.pbp.simulator.Scenario;
import bank.pbp.simulator.WebhookSender;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Finastra Fusion Essence stand-in: parties, accounts, balances, holds, same-bank transfers,
 * outbound UK payments with asynchronous scheme status, and inbound credits.
 * Field names are placeholders until the Essence API specification is received.
 *
 * <p>Available balance = ledger balance − open holds.
 */
@RestController
@RequestMapping("/essence/v1")
public class EssenceSimulator {

    public static final String BANK_SORT_CODE = "200001";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> byRequestId = new ConcurrentHashMap<>();
    /** Every posting, as Essence would report them for reconciliation (GET /postings?date=). */
    private final List<Map<String, Object>> postings = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final WebhookSender webhooks;
    private final String webhookUrl;
    private final SimulatorState state;

    /** The market's currency and local time (docs/design/market-packs.md); GBP and London in the UK. */
    private final String currency;
    private final java.time.ZoneId zone;
    private final bank.pbp.common.market.MarketPack market;

    public EssenceSimulator(WebhookSender webhooks, @Value("${sim.essence.webhook-url}") String webhookUrl,
                            @Value("${sim.state-file:}") String stateFile,
                            com.fasterxml.jackson.databind.ObjectMapper mapper,
                            bank.pbp.common.market.MarketPack market) {
        this.currency = market.currency().getCurrencyCode();
        this.zone = market.zone();
        this.market = market;
        this.webhooks = webhooks;
        this.webhookUrl = webhookUrl;
        reset();
        this.state = new SimulatorState(stateFile, mapper, this::snapshot, this::restore);
    }

    /**
     * Accounts, holds, postings and Direct Debits survive a restart (a rebuild with make up), as
     * they would in a real Essence sandbox. Saved every two seconds and on shutdown.
     */
    synchronized Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("accounts", accounts.values().stream().map(a -> Map.of("id", a.id, "accountNumber", a.accountNumber,
                "name", a.name, "ledger", a.ledger.toPlainString())).toList());
        s.put("holds", holds.values().stream().map(h -> Map.of("id", h.id(), "accountId", h.accountId(),
                "amount", h.amount().toPlainString())).toList());
        s.put("postings", List.copyOf(postings));
        s.put("mandates", mandates.values().stream().map(m -> Map.of("ref", m.ref, "accountId", m.accountId,
                "originatorName", m.originatorName, "serviceUserNumber", m.serviceUserNumber,
                "payerReference", m.payerReference, "status", m.status)).toList());
        s.put("collections", collections.values().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", c.ref);
            m.put("mandateRef", c.mandateRef);
            m.put("accountId", c.accountId);
            m.put("amount", c.amount.toPlainString());
            m.put("status", c.status);
            m.put("postingRef", c.postingRef);
            return m;
        }).toList());
        return s;
    }

    @SuppressWarnings("unchecked")
    synchronized void restore(Map<String, Object> s) {
        for (Map<String, Object> a : (List<Map<String, Object>>) s.getOrDefault("accounts", List.of())) {
            accounts.put(str(a.get("id")), new Account(str(a.get("id")), str(a.get("accountNumber")), str(a.get("name")),
                    str(a.get("ledger"))));
        }
        for (Map<String, Object> h : (List<Map<String, Object>>) s.getOrDefault("holds", List.of())) {
            holds.put(str(h.get("id")), new Hold(str(h.get("id")), str(h.get("accountId")), new BigDecimal(str(h.get("amount")))));
        }
        postings.addAll((List<Map<String, Object>>) s.getOrDefault("postings", List.of()));
        for (Map<String, Object> m : (List<Map<String, Object>>) s.getOrDefault("mandates", List.of())) {
            Mandate mandate = new Mandate(str(m.get("ref")), str(m.get("accountId")), str(m.get("originatorName")),
                    str(m.get("serviceUserNumber")), str(m.get("payerReference")));
            mandate.status = str(m.get("status"));
            mandates.put(mandate.ref, mandate);
        }
        for (Map<String, Object> c : (List<Map<String, Object>>) s.getOrDefault("collections", List.of())) {
            Collection col = new Collection(str(c.get("ref")), str(c.get("mandateRef")), str(c.get("accountId")),
                    new BigDecimal(str(c.get("amount"))));
            col.status = str(c.get("status"));
            col.postingRef = c.get("postingRef") == null ? null : str(c.get("postingRef"));
            collections.put(col.ref, col);
        }
    }

    private static String str(Object o) {
        return String.valueOf(o);
    }

    public final synchronized void reset() {
        accounts.clear();
        holds.clear();
        byRequestId.clear();
        postings.clear();
        if (mandates != null) {
            mandates.clear();
            collections.clear();
        }
        for (Account a : List.of(
                new Account("ACC-5501", "41550123", "Priya Shah", "1000.00"),
                new Account("ACC-5510", "41550198", "Ravi Kumar", "800.00"),
                new Account("ACC-5530", "41550301", "Meera Nair", "300.00"),
                new Account("ACC-7001", "71700011", "Sharma Traders Ltd", "25000.00"),
                // The bank's own account that credit card repayments are paid into (then posted to Thredd).
                new Account("ACC-CARDS", "41559999", "PBP Card Repayments", "0.00"))) {
            accounts.put(a.id, a);
        }
    }

    // ---------- parties and accounts ----------

    @PostMapping("/parties")
    public ResponseEntity<?> createParty(@RequestBody Map<String, Object> req,
                                         @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Map<String, Object> cached = byRequestId.get("party:" + req.get("clientRequestId"));
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }
        Map<String, Object> body = Map.of("partyId", "ESS-PTY-" + (10000 + RANDOM.nextInt(90000)),
                "name", String.valueOf(req.get("name")), "status", "ACTIVE");
        byRequestId.put("party:" + req.get("clientRequestId"), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/accounts")
    public synchronized ResponseEntity<?> openAccount(@RequestBody Map<String, Object> req,
                                                      @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Map<String, Object> cached = byRequestId.get("account:" + req.get("clientRequestId"));
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }
        String id;
        do {
            id = "ACC-" + (6000 + RANDOM.nextInt(3999));
        } while (accounts.containsKey(id));
        String number;
        do {
            number = String.valueOf(40_000_000 + RANDOM.nextInt(9_999_999));
        } while (findByNumber(BANK_SORT_CODE, number).isPresent());
        Account a = new Account(id, number, String.valueOf(req.getOrDefault("name", "")), "0.00");
        accounts.put(id, a);
        Map<String, Object> body = Map.of("accountId", id, "sortCode", BANK_SORT_CODE, "accountNumber", number,
                "partyId", String.valueOf(req.get("partyId")), "status", "ACTIVE");
        byRequestId.put("account:" + req.get("clientRequestId"), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<?> balance(@PathVariable String accountId,
                                     @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Account a = accounts.get(accountId);
        if (a == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account " + accountId);
        }
        return ResponseEntity.ok(Map.of("accountId", a.id, "available", available(a).toPlainString(),
                "ledger", a.ledger.toPlainString(), "currency", currency));
    }

    // ---------- holds (card authorisations) ----------

    @PostMapping("/accounts/{accountId}/holds")
    public synchronized ResponseEntity<?> placeHold(@PathVariable String accountId, @RequestBody Map<String, Object> req,
                                                    @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Map<String, Object> cached = byRequestId.get("hold:" + req.get("clientRequestId"));
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }
        Account a = accounts.get(accountId);
        if (a == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account " + accountId);
        }
        BigDecimal amount = new BigDecimal(String.valueOf(req.get("amount")));
        if (available(a).compareTo(amount) < 0) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Available balance is below the amount");
        }
        Hold h = new Hold("HLD-" + shortId(), accountId, amount);
        holds.put(h.id, h);
        Map<String, Object> body = Map.of("holdId", h.id, "accountId", accountId, "amount", amount.toPlainString(),
                "available", available(a).toPlainString());
        byRequestId.put("hold:" + req.get("clientRequestId"), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Presentment: turns the hold into a posted debit for the final amount. */
    @PostMapping("/holds/{holdId}/capture")
    public synchronized ResponseEntity<?> capture(@PathVariable String holdId, @RequestBody Map<String, Object> req) {
        Hold h = holds.remove(holdId);
        if (h == null) {
            return error(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "No open hold " + holdId);
        }
        Account a = accounts.get(h.accountId);
        BigDecimal amount = req.get("amount") == null ? h.amount : new BigDecimal(String.valueOf(req.get("amount")));
        a.ledger = a.ledger.subtract(amount);
        String postingRef = "PST-" + shortId();
        post(a.id, amount.negate(), postingRef, "CARD_PRESENTMENT");
        return ResponseEntity.ok(Map.of("postingRef", postingRef, "amount", amount.toPlainString()));
    }

    @DeleteMapping("/holds/{holdId}")
    public synchronized ResponseEntity<?> release(@PathVariable String holdId) {
        Hold h = holds.remove(holdId);
        return h == null
                ? error(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "No open hold " + holdId)
                : ResponseEntity.ok(Map.of("holdId", holdId, "status", "RELEASED"));
    }

    // ---------- same-bank transfer ----------

    @PostMapping("/transfers")
    public synchronized ResponseEntity<?> transfer(@RequestBody TransferRequest req,
                                                   @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        Map<String, Object> previous = byRequestId.get("transfer:" + req.clientRequestId());
        if (previous != null) {
            return ResponseEntity.ok(previous);
        }
        Account debit = accounts.get(req.debitAccountId());
        if (debit == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No debit account " + req.debitAccountId());
        }
        Optional<Account> credit = findByNumber(req.creditSortCode(), req.creditAccountNumber());
        if (credit.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No credit account for the sort code and account number");
        }
        BigDecimal amount = new BigDecimal(req.amount());
        if (available(debit).compareTo(amount) < 0) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Available balance is below the amount");
        }
        debit.ledger = debit.ledger.subtract(amount);
        credit.get().ledger = credit.get().ledger.add(amount);
        post(debit.id, amount.negate(), req.clientRequestId(), "TRANSFER_OUT");
        post(credit.get().id, amount, req.clientRequestId(), "TRANSFER_IN");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("postingRef", "PST-" + shortId());
        body.put("status", "POSTED");
        body.put("debitAccountId", debit.id);
        body.put("creditAccountId", credit.get().id);
        byRequestId.put("transfer:" + req.clientRequestId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ---------- outbound payments (in the UK Finastra routes to FPS, CHAPS or Bacs) ----------

    /**
     * Accepts the payment, debits the account, and reports scheme status by webhook:
     * ACCEPTED after 1 s, then COMPLETED after 2 s. A creditor sort code starting with 99, or the
     * scenario {@code scheme-reject}, is rejected by the scheme and the debit is reversed.
     */
    @PostMapping("/payments")
    public synchronized ResponseEntity<?> submitPayment(@RequestBody Map<String, Object> req,
                                                        @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        if (!"scheme-reject".equals(scenario)) {
            Scenario.apply(scenario);
        }
        Map<String, Object> previous = byRequestId.get("payment:" + req.get("clientRequestId"));
        if (previous != null) {
            return ResponseEntity.ok(previous);
        }
        Account debit = accounts.get(String.valueOf(req.get("debitAccountId")));
        if (debit == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No debit account " + req.get("debitAccountId"));
        }
        BigDecimal amount = new BigDecimal(String.valueOf(req.get("amount")));
        if (available(debit).compareTo(amount) < 0) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Available balance is below the amount");
        }
        debit.ledger = debit.ledger.subtract(amount);
        post(debit.id, amount.negate(), String.valueOf(req.get("endToEndId")), "PAYMENT_OUT");
        String finastraRef = "FIN-" + shortId();
        String creditorSortCode = String.valueOf(req.get("creditorSortCode"));
        // UK payees are a sort code and account number; other markets send creditorAccount ({scheme, fields}).
        // A UK sort code starting 99, or the scenario scheme-reject in any market, is rejected by the scheme.
        boolean reject = "scheme-reject".equals(scenario) || creditorSortCode.startsWith("99");
        // The bank chooses the scheme; the simulator only checks it is one of the market's (FPS or CHAPS in the UK)
        // and otherwise reports the market's default instant scheme, as Finastra would route it.
        String scheme = outboundScheme(req.get("scheme"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("finastraRef", finastraRef);
        body.put("status", "SUBMITTED");
        body.put("scheme", scheme);
        byRequestId.put("payment:" + req.get("clientRequestId"), body);

        String endToEndId = String.valueOf(req.get("endToEndId"));
        webhooks.send(webhookUrl, statusEvent(finastraRef, endToEndId, scheme, "ACCEPTED", null), 1000);
        if (reject) {
            webhooks.send(webhookUrl, statusEvent(finastraRef, endToEndId, scheme, "REJECTED",
                    "AC01 Incorrect account number"), 2000);
            synchronized (this) {
                debit.ledger = debit.ledger.add(amount);
                post(debit.id, amount, endToEndId, "PAYMENT_RETURN");
            }
        } else {
            webhooks.send(webhookUrl, statusEvent(finastraRef, endToEndId, scheme, "COMPLETED", null), 3000);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /** The requested scheme if the market has it (not same-bank); otherwise the market's default (FPS in the UK). */
    String outboundScheme(Object requested) {
        String code = requested == null ? null : String.valueOf(requested);
        return market.paymentScheme(code).filter(s -> !"INTERNAL".equals(s.code()))
                .map(bank.pbp.common.market.PaymentScheme::code)
                .orElseGet(() -> market.customerChoices().stream().filter(bank.pbp.common.market.PaymentScheme::instant)
                        .findFirst().or(() -> market.customerChoices().stream().findFirst())
                        .map(bank.pbp.common.market.PaymentScheme::code).orElse("FPS"));
    }

    private static Map<String, Object> statusEvent(String ref, String endToEndId, String scheme, String status,
                                                   String reason) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", "EVT-" + shortId());
        e.put("type", "payment.status");
        e.put("finastraRef", ref);
        e.put("endToEndId", endToEndId);
        e.put("scheme", scheme);
        e.put("status", status);
        e.put("reason", reason);
        return e;
    }

    // ---------- inbound credits (demo toolbar "Receive £20") ----------

    /** Simulates a Faster Payment arriving from another bank: credits the account and notifies the bank. */
    @PostMapping("/sim/inbound")
    public synchronized ResponseEntity<?> inbound(@RequestBody Map<String, Object> req) {
        Account a = accounts.get(String.valueOf(req.get("creditAccountId")));
        if (a == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account " + req.get("creditAccountId"));
        }
        BigDecimal amount = new BigDecimal(String.valueOf(req.getOrDefault("amount", "20.00")));
        a.ledger = a.ledger.add(amount);
        String inRef = ("BACS".equals(req.get("scheme")) ? "BACS-IN-" : "FIN-IN-") + shortId();
        post(a.id, amount, inRef, "PAYMENT_IN");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", "EVT-" + shortId());
        e.put("type", "credit.received");
        e.put("finastraRef", inRef);
        e.put("scheme", "BACS".equals(req.get("scheme")) ? "BACS" : "FPS");
        e.put("creditAccountId", a.id);
        e.put("amount", amount.toPlainString());
        e.put("currency", currency);
        e.put("debtorName", String.valueOf(req.getOrDefault("debtorName", "J Smith")));
        e.put("debtorSortCode", String.valueOf(req.getOrDefault("debtorSortCode", "040004")));
        e.put("reference", String.valueOf(req.getOrDefault("reference", "Payment")));
        webhooks.send(webhookUrl, e, 200);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(e);
    }

    // ---------- Bacs Direct Credits (salary runs) ----------

    /**
     * A Bacs credit file from an employer: every line credits an account on day 3 of the cycle and
     * notifies the bank as {@code credit.received} with scheme BACS. The simulator posts them at once.
     */
    @PostMapping("/sim/bacs/credits")
    public synchronized ResponseEntity<?> bacsCredits(@RequestBody Map<String, Object> req) {
        Object lines = req.get("credits");
        if (!(lines instanceof List<?> list) || list.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_FILE", "credits must be a non-empty list");
        }
        String originator = String.valueOf(req.getOrDefault("originatorName", "ACME LTD"));
        List<Object> results = new java.util.ArrayList<>();
        for (Object line : list) {
            @SuppressWarnings("unchecked")
            Map<String, Object> l = new LinkedHashMap<>((Map<String, Object>) line);
            l.put("scheme", "BACS");
            l.putIfAbsent("debtorName", originator);
            l.putIfAbsent("debtorSortCode", "309634");
            l.putIfAbsent("reference", "SALARY");
            ResponseEntity<?> r = inbound(l);
            results.add(r.getBody());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("originatorName", originator, "credits", results));
    }

    // ---------- Confirmation of Payee ----------

    /** Accounts at other banks the simulated CoP directory knows. Anything else at another bank matches. */
    private static final Map<String, String> COP_DIRECTORY = Map.of(
            "040004:12345678", "Arjun Mehta",
            "309634:55667788", "Northwind Lettings Ltd");

    /**
     * Name check against the account holder (Pay.UK CoP rules, simplified): exact after
     * normalising → MATCH; same surname and initial, or one or two letters out → CLOSE_MATCH with
     * the real name; otherwise NO_MATCH. Sort codes 98-xx-xx belong to a bank outside CoP
     * (UNAVAILABLE); account number 00000000 does not exist (NO_MATCH, AC01).
     */
    @PostMapping("/cop")
    public ResponseEntity<?> confirmPayee(@RequestBody Map<String, Object> req,
                                          @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        if ("cop-unavailable".equals(scenario)) {
            return ResponseEntity.ok(Map.of("result", "UNAVAILABLE", "reasonCode", "SCNS"));
        }
        Scenario.apply(scenario);
        String name = String.valueOf(req.getOrDefault("name", ""));
        String sortCode = String.valueOf(req.get("sortCode"));
        String number = String.valueOf(req.get("accountNumber"));
        String holder;
        if (BANK_SORT_CODE.equals(sortCode)) {
            holder = findByNumber(sortCode, number).map(a -> a.name).orElse(null);
        } else if (sortCode.startsWith("98")) {
            return ResponseEntity.ok(Map.of("result", "UNAVAILABLE", "reasonCode", "SCNS"));
        } else if ("00000000".equals(number)) {
            holder = null;
        } else {
            holder = COP_DIRECTORY.getOrDefault(sortCode + ":" + number, name);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (holder == null) {
            body.put("result", "NO_MATCH");
            body.put("reasonCode", "AC01");
        } else {
            String verdict = CopNames.compare(name, holder);
            body.put("result", verdict);
            if ("CLOSE_MATCH".equals(verdict)) {
                body.put("matchedName", holder);
                body.put("reasonCode", "MBAM");
            } else if ("NO_MATCH".equals(verdict)) {
                body.put("reasonCode", "ANNM");
            }
        }
        return ResponseEntity.ok(body);
    }

    // ---------- fees ----------

    /** A charge the bank's tariff applies (e.g. the CHAPS fee). Fees post even if they take the account overdrawn. */
    @PostMapping("/accounts/{accountId}/fees")
    public synchronized ResponseEntity<?> fee(@PathVariable String accountId, @RequestBody Map<String, Object> req) {
        Map<String, Object> cached = byRequestId.get("fee:" + req.get("clientRequestId"));
        if (cached != null) {
            return ResponseEntity.ok(cached);
        }
        Account a = accounts.get(accountId);
        if (a == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account " + accountId);
        }
        BigDecimal amount = new BigDecimal(String.valueOf(req.get("amount")));
        a.ledger = a.ledger.subtract(amount);
        Map<String, Object> posting = post(a.id, amount.negate(), String.valueOf(req.get("reference")), "FEE");
        Map<String, Object> body = Map.of("postingRef", posting.get("postingId"), "amount", amount.toPlainString());
        byRequestId.put("fee:" + req.get("clientRequestId"), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ---------- Direct Debits (Bacs, the bank is the payer's bank) ----------

    private final Map<String, Mandate> mandates = new ConcurrentHashMap<>();
    private final Map<String, Collection> collections = new ConcurrentHashMap<>();

    /**
     * Demo control: an originator (e.g. an energy company) lodges a new Direct Debit Instruction
     * through AUDDIS (transaction code 0N). Essence notifies the bank with {@code dd.mandate}.
     */
    @PostMapping("/sim/dd/mandates")
    public synchronized ResponseEntity<?> lodgeMandate(@RequestBody Map<String, Object> req) {
        Account a = accounts.get(String.valueOf(req.get("accountId")));
        if (a == null) {
            return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "No account " + req.get("accountId"));
        }
        Mandate m = new Mandate("DDM-" + shortId(), a.id,
                String.valueOf(req.getOrDefault("originatorName", "Brightside Energy")),
                String.valueOf(req.getOrDefault("serviceUserNumber", "123456")),
                String.valueOf(req.getOrDefault("payerReference", "CUST-" + shortId())));
        mandates.put(m.ref, m);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", "EVT-" + shortId());
        e.put("type", "dd.mandate");
        e.put("auddisCode", "0N");
        e.put("mandateRef", m.ref);
        e.put("accountId", a.id);
        e.put("originatorName", m.originatorName);
        e.put("serviceUserNumber", m.serviceUserNumber);
        e.put("payerReference", m.payerReference);
        webhooks.send(webhookUrl, e, 200);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(e);
    }

    /**
     * Demo control: the originator submits a collection (Bacs day 1). Essence presents it to the
     * bank with {@code dd.collection} and its due date (day 3); the bank answers pay or return.
     * Collections are presented even on a cancelled mandate, as happens in Bacs.
     */
    @PostMapping("/sim/dd/collections")
    public synchronized ResponseEntity<?> presentCollection(@RequestBody Map<String, Object> req) {
        Mandate m = mandates.get(String.valueOf(req.get("mandateRef")));
        if (m == null) {
            return error(HttpStatus.NOT_FOUND, "MANDATE_NOT_FOUND", "No mandate " + req.get("mandateRef"));
        }
        BigDecimal amount = new BigDecimal(String.valueOf(req.getOrDefault("amount", "30.00")));
        String due = String.valueOf(req.getOrDefault("dueDate",
                java.time.LocalDate.now(zone).toString()));
        Collection c = new Collection("DDC-" + shortId(), m.ref, m.accountId, amount);
        collections.put(c.ref, c);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", "EVT-" + shortId());
        e.put("type", "dd.collection");
        e.put("collectionRef", c.ref);
        e.put("mandateRef", m.ref);
        e.put("accountId", m.accountId);
        e.put("amount", amount.toPlainString());
        e.put("currency", currency);
        e.put("dueDate", due);
        e.put("originatorName", m.originatorName);
        e.put("serviceUserNumber", m.serviceUserNumber);
        e.put("payerReference", m.payerReference);
        webhooks.send(webhookUrl, e, 200);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(e);
    }

    /** The bank pays the collection: Essence debits the account (posting ref = collection ref). */
    @PostMapping("/direct-debits/collections/{ref}/pay")
    public synchronized ResponseEntity<?> payCollection(@PathVariable String ref) {
        Collection c = collections.get(ref);
        if (c == null) {
            return error(HttpStatus.NOT_FOUND, "COLLECTION_NOT_FOUND", "No collection " + ref);
        }
        if ("PAID".equals(c.status)) {
            return ResponseEntity.ok(Map.of("collectionRef", ref, "status", "PAID", "postingRef", c.postingRef));
        }
        if (!"PRESENTED".equals(c.status)) {
            return error(HttpStatus.CONFLICT, "INVALID_STATE", "Collection is " + c.status);
        }
        Account a = accounts.get(c.accountId);
        if (available(a).compareTo(c.amount) < 0) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Available balance is below the amount");
        }
        a.ledger = a.ledger.subtract(c.amount);
        post(a.id, c.amount.negate(), c.ref, "DD_COLLECTION");
        c.status = "PAID";
        c.postingRef = "PST-" + shortId();
        return ResponseEntity.ok(Map.of("collectionRef", ref, "status", "PAID", "postingRef", c.postingRef));
    }

    /** The bank returns the collection unpaid (ARUDD to the originator) with a Bacs reason code. */
    @PostMapping("/direct-debits/collections/{ref}/return")
    public synchronized ResponseEntity<?> returnCollection(@PathVariable String ref, @RequestBody Map<String, Object> req) {
        Collection c = collections.get(ref);
        if (c == null) {
            return error(HttpStatus.NOT_FOUND, "COLLECTION_NOT_FOUND", "No collection " + ref);
        }
        if ("PAID".equals(c.status)) {
            return error(HttpStatus.CONFLICT, "INVALID_STATE", "Collection already paid");
        }
        c.status = "RETURNED";
        return ResponseEntity.ok(Map.of("collectionRef", ref, "status", "RETURNED",
                "arudd", String.valueOf(req.getOrDefault("reasonCode", "0"))));
    }

    /**
     * Direct Debit Guarantee refund: Essence credits the payer at once (posting ref = collection
     * ref, so collection and refund net to zero) and raises an indemnity claim (DDICA) on the originator.
     */
    @PostMapping("/direct-debits/collections/{ref}/indemnity")
    public synchronized ResponseEntity<?> indemnity(@PathVariable String ref, @RequestBody Map<String, Object> req) {
        Collection c = collections.get(ref);
        if (c == null) {
            return error(HttpStatus.NOT_FOUND, "COLLECTION_NOT_FOUND", "No collection " + ref);
        }
        if ("REFUNDED".equals(c.status)) {
            return ResponseEntity.ok(Map.of("collectionRef", ref, "status", "REFUNDED"));
        }
        if (!"PAID".equals(c.status)) {
            return error(HttpStatus.CONFLICT, "INVALID_STATE", "Only a paid collection can be refunded");
        }
        Account a = accounts.get(c.accountId);
        a.ledger = a.ledger.add(c.amount);
        post(a.id, c.amount, c.ref, "DD_INDEMNITY");
        c.status = "REFUNDED";
        return ResponseEntity.ok(Map.of("collectionRef", ref, "status", "REFUNDED",
                "ddicaReason", String.valueOf(req.getOrDefault("reasonCode", "1")),
                "claimRef", "DDICA-" + shortId()));
    }

    /** The payer cancelled the mandate at the bank: Essence sends ADDACS (code 1) to the originator. */
    @PostMapping("/direct-debits/mandates/{ref}/cancel")
    public synchronized ResponseEntity<?> cancelMandate(@PathVariable String ref, @RequestBody(required = false) Map<String, Object> req) {
        Mandate m = mandates.get(ref);
        if (m == null) {
            return error(HttpStatus.NOT_FOUND, "MANDATE_NOT_FOUND", "No mandate " + ref);
        }
        m.status = "CANCELLED";
        return ResponseEntity.ok(Map.of("mandateRef", ref, "status", "CANCELLED",
                "addacs", req == null ? "1" : String.valueOf(req.getOrDefault("reasonCode", "1"))));
    }

    static final class Mandate {
        final String ref;
        final String accountId;
        final String originatorName;
        final String serviceUserNumber;
        final String payerReference;
        String status = "ACTIVE";

        Mandate(String ref, String accountId, String originatorName, String serviceUserNumber, String payerReference) {
            this.ref = ref;
            this.accountId = accountId;
            this.originatorName = originatorName;
            this.serviceUserNumber = serviceUserNumber;
            this.payerReference = payerReference;
        }
    }

    static final class Collection {
        final String ref;
        final String mandateRef;
        final String accountId;
        final BigDecimal amount;
        String status = "PRESENTED";
        String postingRef;

        Collection(String ref, String mandateRef, String accountId, BigDecimal amount) {
            this.ref = ref;
            this.mandateRef = mandateRef;
            this.accountId = accountId;
            this.amount = amount;
        }
    }

    // ---------- postings (reconciliation source) ----------

    @GetMapping("/postings")
    public List<Map<String, Object>> postings(@org.springframework.web.bind.annotation.RequestParam String date) {
        return postings.stream().filter(p -> date.equals(p.get("date"))).toList();
    }

    /** Demo control: a posting nobody at the bank asked for, so reconciliation has something to find. */
    @PostMapping("/sim/rogue-posting")
    public synchronized Map<String, Object> rogue(@RequestBody Map<String, Object> req) {
        Account a = accounts.get(String.valueOf(req.getOrDefault("accountId", "ACC-5501")));
        BigDecimal amount = new BigDecimal(String.valueOf(req.getOrDefault("amount", "7.77")));
        a.ledger = a.ledger.subtract(amount);
        return post(a.id, amount.negate(), "ROGUE-" + shortId(), "MANUAL_ADJUSTMENT");
    }

    private Map<String, Object> post(String accountId, BigDecimal signedAmount, String ref, String type) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("postingId", "PST-" + shortId());
        p.put("date", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        p.put("accountId", accountId);
        p.put("amount", signedAmount.toPlainString());
        p.put("ref", ref);
        p.put("type", type);
        postings.add(p);
        return p;
    }

    // ---------- helpers ----------

    private Optional<Account> findByNumber(String sortCode, String number) {
        return accounts.values().stream()
                .filter(a -> BANK_SORT_CODE.equals(sortCode) && a.accountNumber.equals(number))
                .findFirst();
    }

    private BigDecimal available(Account a) {
        BigDecimal held = holds.values().stream().filter(h -> h.accountId.equals(a.id))
                .map(h -> h.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return a.ledger.subtract(held);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("errorCode", code, "message", message));
    }

    public record TransferRequest(String debitAccountId, String creditSortCode, String creditAccountNumber,
                                  String amount, String currency, String reference, String clientRequestId) {
    }

    static final class Account {
        final String id;
        final String accountNumber;
        final String name;
        BigDecimal ledger;

        Account(String id, String accountNumber, String name, String ledger) {
            this.id = id;
            this.accountNumber = accountNumber;
            this.name = name;
            this.ledger = new BigDecimal(ledger);
        }
    }

    record Hold(String id, String accountId, BigDecimal amount) {
    }
}
