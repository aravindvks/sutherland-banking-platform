package bank.pbp.simulator.thredd;

import bank.pbp.simulator.WebhookSender;
import bank.pbp.simulator.essence.SimulatorState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Thredd credit processing stand-in: the credit card account is held by Thredd, not Essence. Thredd
 * owns the credit rules: open-to-buy (limit minus balance minus pending authorisations), interest,
 * the minimum payment and the statement cycle. Field names are placeholders until Thredd's credit
 * API specification arrives.
 *
 * <p>Statement rules (simulated):
 * <ul>
 *   <li>Interest: purchase APR / 12 on any part of the previous statement balance not repaid by its
 *       due date (none while statements are paid in full).</li>
 *   <li>Minimum payment: the greater of £5 and 1% of the balance plus interest and fees charged this
 *       cycle, never more than the balance.</li>
 *   <li>Due 25 days after the statement; {@code dueInSeconds} on the demo endpoint brings it forward.</li>
 *   <li>Status at any time: PAID_IN_FULL (repaid at least the balance since the statement),
 *       MINIMUM_PAID, or DUE / MISSED before or after the due time.</li>
 * </ul>
 */
@RestController
@RequestMapping("/thredd/v1/credit")
public class ThreddCreditSimulator {

    private static final BigDecimal MIN_FLOOR = new BigDecimal("5.00");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Hold> holds = new LinkedHashMap<>();
    private final Map<String, Statement> statements = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
    private final WebhookSender webhooks;
    private final String webhookUrl;
    @SuppressWarnings("unused")
    private final SimulatorState state;

    private final ZoneId zone;

    public ThreddCreditSimulator(WebhookSender webhooks, @Value("${sim.thredd.webhook-url}") String webhookUrl,
                                 @Value("${sim.thredd.state-file:}") String stateFile, ObjectMapper mapper,
                                 bank.pbp.common.market.MarketPack market) {
        this.zone = market.zone();   // statement and due dates in the market's local time
        this.webhooks = webhooks;
        this.webhookUrl = webhookUrl;
        this.state = new SimulatorState(stateFile, mapper, this::snapshot, this::restore);
    }

    // ---------- accounts ----------

    @PostMapping("/accounts")
    public synchronized ResponseEntity<Map<String, Object>> open(@RequestBody Map<String, Object> req,
                                                                 @RequestHeader(value = "IdempotencyKey", required = false) String key) {
        if (key != null && byKey.containsKey("open:" + key)) {
            return ResponseEntity.ok(byKey.get("open:" + key));
        }
        Account a = new Account("thr_cca_" + hex(), str(req.get("customerRef")), money(req.get("creditLimit")),
                new BigDecimal(str(req.getOrDefault("purchaseApr", "24.9"))));
        accounts.put(a.id, a);
        Map<String, Object> body = view(a);
        if (key != null) {
            byKey.put("open:" + key, body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/accounts/{id}")
    public synchronized ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        Account a = accounts.get(id);
        return a == null ? notFound(id) : ResponseEntity.ok(view(a));
    }

    @PostMapping("/accounts/{id}/limit")
    public synchronized ResponseEntity<Map<String, Object>> limit(@PathVariable String id, @RequestBody Map<String, Object> req) {
        Account a = accounts.get(id);
        if (a == null) {
            return notFound(id);
        }
        a.limit = money(req.get("creditLimit"));
        return ResponseEntity.ok(view(a));
    }

    /** Closes the account; refused while anything is owed or pending. */
    @PostMapping("/accounts/{id}/close")
    public synchronized ResponseEntity<Map<String, Object>> close(@PathVariable String id) {
        Account a = accounts.get(id);
        if (a == null) {
            return notFound(id);
        }
        if (a.balance.signum() != 0 || pending(a).signum() != 0) {
            return problem(HttpStatus.CONFLICT, "CARD-CREDIT-003", "Balance outstanding",
                    "The account can only close with a zero balance and no pending authorisations");
        }
        a.status = "CLOSED";
        return ResponseEntity.ok(view(a));
    }

    // ---------- authorisations (open-to-buy) ----------

    @PostMapping("/accounts/{id}/holds")
    public synchronized ResponseEntity<Map<String, Object>> hold(@PathVariable String id, @RequestBody Map<String, Object> req,
                                                                 @RequestHeader(value = "IdempotencyKey", required = false) String key) {
        if (key != null && byKey.containsKey("hold:" + key)) {
            return ResponseEntity.ok(byKey.get("hold:" + key));
        }
        Account a = accounts.get(id);
        if (a == null) {
            return notFound(id);
        }
        BigDecimal amount = money(req.get("amount"));
        if (!"ACTIVE".equals(a.status)) {
            return problem(HttpStatus.UNPROCESSABLE_ENTITY, "CARD-CREDIT-002", "Account not open", "Account is " + a.status);
        }
        if (available(a).compareTo(amount) < 0) {
            return problem(HttpStatus.UNPROCESSABLE_ENTITY, "CARD-CREDIT-001", "Over credit limit",
                    "Available credit " + available(a).toPlainString() + " is below " + amount.toPlainString());
        }
        Hold h = new Hold("thr_hold_" + hex(), id, amount, str(req.getOrDefault("reference", "Card")));
        holds.put(h.id, h);
        Map<String, Object> body = Map.of("holdId", h.id, "availableCredit", available(a).toPlainString());
        if (key != null) {
            byKey.put("hold:" + key, body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Presentment: the hold becomes a purchase on the account (posted balance). */
    @PostMapping("/holds/{holdId}/capture")
    public synchronized ResponseEntity<Map<String, Object>> capture(@PathVariable String holdId, @RequestBody Map<String, Object> req) {
        Hold h = holds.remove(holdId);
        if (h == null) {
            return notFound(holdId);
        }
        Account a = accounts.get(h.accountId);
        BigDecimal amount = req.get("amount") == null ? h.amount : money(req.get("amount"));
        String ref = "thr_txn_" + hex();
        a.balance = a.balance.add(amount);
        a.transactions.add(txn(ref, "PURCHASE", amount, h.reference));
        return ResponseEntity.ok(Map.of("postingRef", ref, "balance", a.balance.toPlainString()));
    }

    @DeleteMapping("/holds/{holdId}")
    public synchronized ResponseEntity<Map<String, Object>> release(@PathVariable String holdId) {
        Hold h = holds.remove(holdId);
        return h == null ? notFound(holdId) : ResponseEntity.ok(Map.of("holdId", holdId, "status", "RELEASED"));
    }

    // ---------- repayments and fees ----------

    @PostMapping("/accounts/{id}/repayments")
    public synchronized ResponseEntity<Map<String, Object>> repay(@PathVariable String id, @RequestBody Map<String, Object> req,
                                                                  @RequestHeader(value = "IdempotencyKey", required = false) String key) {
        if (key != null && byKey.containsKey("repay:" + key)) {
            return ResponseEntity.ok(byKey.get("repay:" + key));
        }
        Account a = accounts.get(id);
        if (a == null) {
            return notFound(id);
        }
        BigDecimal amount = money(req.get("amount"));
        String ref = "thr_rep_" + hex();
        a.balance = a.balance.subtract(amount);
        a.repaidSinceStatement = a.repaidSinceStatement.add(amount);
        a.transactions.add(txn(ref, "REPAYMENT", amount.negate(), str(req.getOrDefault("reference", "Repayment"))));
        Map<String, Object> body = Map.of("repaymentRef", ref, "balance", a.balance.toPlainString());
        if (key != null) {
            byKey.put("repay:" + key, body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** A fee from the bank's tariff (late payment). The bank decides the fee; Thredd posts it. */
    @PostMapping("/accounts/{id}/fees")
    public synchronized ResponseEntity<Map<String, Object>> fee(@PathVariable String id, @RequestBody Map<String, Object> req,
                                                                @RequestHeader(value = "IdempotencyKey", required = false) String key) {
        if (key != null && byKey.containsKey("fee:" + key)) {
            return ResponseEntity.ok(byKey.get("fee:" + key));
        }
        Account a = accounts.get(id);
        if (a == null) {
            return notFound(id);
        }
        BigDecimal amount = money(req.get("amount"));
        String ref = "thr_fee_" + hex();
        a.balance = a.balance.add(amount);
        a.feesThisCycle = a.feesThisCycle.add(amount);
        a.transactions.add(txn(ref, "FEE", amount, str(req.getOrDefault("type", "FEE"))));
        Map<String, Object> body = Map.of("feeRef", ref, "balance", a.balance.toPlainString());
        if (key != null) {
            byKey.put("fee:" + key, body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // ---------- statements ----------

    @GetMapping("/statements/{statementId}")
    public synchronized ResponseEntity<Map<String, Object>> statement(@PathVariable String statementId) {
        Statement s = statements.get(statementId);
        return s == null ? notFound(statementId) : ResponseEntity.ok(view(s));
    }

    /**
     * Demo and test control: Thredd's statement cycle runs now for one account. Interest is charged on
     * what is left unpaid from the previous statement, the statement is cut, and Thredd notifies the
     * bank by webhook ({@code credit.statement}).
     */
    @PostMapping("/sim/statement")
    public synchronized ResponseEntity<Map<String, Object>> cycle(@RequestBody Map<String, Object> req) {
        Account a = accounts.get(str(req.get("accountId")));
        if (a == null) {
            return notFound(str(req.get("accountId")));
        }
        BigDecimal interest = BigDecimal.ZERO.setScale(2);
        Statement prev = a.lastStatementId == null ? null : statements.get(a.lastStatementId);
        if (prev != null) {
            BigDecimal unpaid = prev.balance.subtract(a.repaidSinceStatement);
            if (unpaid.signum() > 0) {
                interest = unpaid.multiply(a.apr).divide(new BigDecimal("1200"), 2, RoundingMode.HALF_UP);
                if (interest.signum() > 0) {
                    a.balance = a.balance.add(interest);
                    a.transactions.add(txn("thr_int_" + hex(), "INTEREST", interest, "Purchase interest"));
                }
            }
        }
        BigDecimal balance = a.balance.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal minimum = balance.multiply(new BigDecimal("0.01")).add(interest).add(a.feesThisCycle)
                .setScale(2, RoundingMode.HALF_UP).max(MIN_FLOOR).min(balance);
        LocalDate dueDate = LocalDate.now(zone).plusDays(Long.parseLong(str(req.getOrDefault("dueInDays", "25"))));
        Instant dueAt = req.get("dueInSeconds") != null
                ? Instant.now().plusSeconds(Long.parseLong(str(req.get("dueInSeconds"))))
                : dueDate.atTime(LocalTime.of(23, 59, 59)).atZone(zone).toInstant();
        Statement s = new Statement("thr_stm_" + hex(), a.id, LocalDate.now(zone), balance, minimum, interest,
                a.feesThisCycle, dueDate, dueAt);
        statements.put(s.id, s);
        a.lastStatementId = s.id;
        a.repaidSinceStatement = BigDecimal.ZERO.setScale(2);
        a.feesThisCycle = BigDecimal.ZERO.setScale(2);

        Map<String, Object> hook = new LinkedHashMap<>(view(s));
        hook.put("type", "credit.statement");
        hook.put("eventId", "thr_evt_" + hex());
        webhooks.send(webhookUrl, hook, 300);
        return ResponseEntity.ok(view(s));
    }

    // ---------- views and helpers ----------

    private Map<String, Object> view(Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", a.id);
        m.put("customerRef", a.customerRef);
        m.put("status", a.status);
        m.put("creditLimit", a.limit.toPlainString());
        m.put("purchaseApr", a.apr.toPlainString());
        m.put("balance", a.balance.toPlainString());
        m.put("pending", pending(a).toPlainString());
        m.put("availableCredit", available(a).toPlainString());
        m.put("lastStatement", a.lastStatementId == null ? null : view(statements.get(a.lastStatementId)));
        m.put("transactions", a.transactions.size() > 20 ? a.transactions.subList(a.transactions.size() - 20,
                a.transactions.size()) : a.transactions);
        return m;
    }

    private Map<String, Object> view(Statement s) {
        Account a = accounts.get(s.accountId);
        boolean latest = s.id.equals(a.lastStatementId);
        BigDecimal repaid = latest ? a.repaidSinceStatement : s.balance; // older statements: superseded
        String status = repaid.compareTo(s.balance) >= 0 ? "PAID_IN_FULL"
                : repaid.compareTo(s.minimum) >= 0 ? "MINIMUM_PAID"
                : Instant.now().isAfter(s.dueAt) ? "MISSED" : "DUE";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statementId", s.id);
        m.put("accountId", s.accountId);
        m.put("statementDate", s.date.toString());
        m.put("balance", s.balance.toPlainString());
        m.put("minimumPayment", s.minimum.toPlainString());
        m.put("interest", s.interest.toPlainString());
        m.put("fees", s.fees.toPlainString());
        m.put("dueDate", s.dueDate.toString());
        m.put("dueAt", s.dueAt.toString());
        m.put("repaid", repaid.toPlainString());
        m.put("status", status);
        return m;
    }

    private BigDecimal pending(Account a) {
        return holds.values().stream().filter(h -> h.accountId.equals(a.id)).map(h -> h.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal available(Account a) {
        return a.limit.subtract(a.balance).subtract(pending(a)).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> txn(String ref, String type, BigDecimal amount, String description) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("ref", ref);
        t.put("type", type);
        t.put("amount", amount.toPlainString());
        t.put("description", description);
        t.put("at", Instant.now().toString());
        return t;
    }

    private static ResponseEntity<Map<String, Object>> notFound(String id) {
        return problem(HttpStatus.NOT_FOUND, "CARD-CREDIT-404", "Not found", "No credit record " + id);
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String code, String title, String detail) {
        return ResponseEntity.status(status).body(Map.of("code", code, "title", title, "detail", detail));
    }

    private static BigDecimal money(Object o) {
        return new BigDecimal(str(o)).setScale(2, RoundingMode.HALF_UP);
    }

    private static String hex() {
        return Integer.toHexString(0x100000 + RANDOM.nextInt(0xEFFFFF));
    }

    private static String str(Object o) {
        return String.valueOf(o);
    }

    // ---------- persistence (survives make up, like the Essence state) ----------

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("accounts", accounts.values().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id);
            m.put("customerRef", a.customerRef);
            m.put("limit", a.limit.toPlainString());
            m.put("apr", a.apr.toPlainString());
            m.put("balance", a.balance.toPlainString());
            m.put("status", a.status);
            m.put("repaid", a.repaidSinceStatement.toPlainString());
            m.put("fees", a.feesThisCycle.toPlainString());
            m.put("lastStatementId", a.lastStatementId);
            m.put("transactions", a.transactions);
            return m;
        }).toList());
        s.put("holds", holds.values().stream().map(h -> Map.of("id", h.id, "accountId", h.accountId,
                "amount", h.amount.toPlainString(), "reference", h.reference)).toList());
        s.put("statements", statements.values().stream().map(st -> Map.of("id", st.id, "accountId", st.accountId,
                "date", st.date.toString(), "balance", st.balance.toPlainString(), "minimum", st.minimum.toPlainString(),
                "interest", st.interest.toPlainString(), "fees", st.fees.toPlainString(),
                "dueDate", st.dueDate.toString(), "dueAt", st.dueAt.toString())).toList());
        return s;
    }

    @SuppressWarnings("unchecked")
    synchronized void restore(Map<String, Object> s) {
        for (Map<String, Object> m : (List<Map<String, Object>>) s.getOrDefault("accounts", List.of())) {
            Account a = new Account(str(m.get("id")), str(m.get("customerRef")), money(m.get("limit")),
                    new BigDecimal(str(m.get("apr"))));
            a.balance = money(m.get("balance"));
            a.status = str(m.get("status"));
            a.repaidSinceStatement = money(m.get("repaid"));
            a.feesThisCycle = money(m.get("fees"));
            a.lastStatementId = m.get("lastStatementId") == null ? null : str(m.get("lastStatementId"));
            a.transactions.addAll((List<Map<String, Object>>) m.getOrDefault("transactions", List.of()));
            accounts.put(a.id, a);
        }
        for (Map<String, Object> m : (List<Map<String, Object>>) s.getOrDefault("holds", List.of())) {
            holds.put(str(m.get("id")), new Hold(str(m.get("id")), str(m.get("accountId")), money(m.get("amount")),
                    str(m.get("reference"))));
        }
        for (Map<String, Object> m : (List<Map<String, Object>>) s.getOrDefault("statements", List.of())) {
            statements.put(str(m.get("id")), new Statement(str(m.get("id")), str(m.get("accountId")),
                    LocalDate.parse(str(m.get("date"))), money(m.get("balance")), money(m.get("minimum")),
                    money(m.get("interest")), money(m.get("fees")), LocalDate.parse(str(m.get("dueDate"))),
                    Instant.parse(str(m.get("dueAt")))));
        }
    }

    static final class Account {
        final String id;
        final String customerRef;
        final BigDecimal apr;
        final List<Map<String, Object>> transactions = new ArrayList<>();
        BigDecimal limit;
        BigDecimal balance = BigDecimal.ZERO.setScale(2);
        BigDecimal repaidSinceStatement = BigDecimal.ZERO.setScale(2);
        BigDecimal feesThisCycle = BigDecimal.ZERO.setScale(2);
        String status = "ACTIVE";
        String lastStatementId;

        Account(String id, String customerRef, BigDecimal limit, BigDecimal apr) {
            this.id = id;
            this.customerRef = customerRef;
            this.limit = limit;
            this.apr = apr;
        }
    }

    record Hold(String id, String accountId, BigDecimal amount, String reference) {
    }

    record Statement(String id, String accountId, LocalDate date, BigDecimal balance, BigDecimal minimum,
                     BigDecimal interest, BigDecimal fees, LocalDate dueDate, Instant dueAt) {
    }
}
