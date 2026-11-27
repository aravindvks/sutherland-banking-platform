package bank.pbp.recon;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Proves every money movement the bank recorded against Essence postings for one business date
 * (LLD section 8). Three runs per date:
 * <ul>
 *   <li>PAYMENTS: same-bank, Faster Payments and CHAPS payments (ref = payment ID; debit = amount
 *       plus any fee), inbound Faster Payments and Bacs credits, and paid Direct Debits (ref =
 *       Finastra reference) → matching Essence debit or credit for the exact amount</li>
 *   <li>CARDS: presentments in auth_log (ref = Essence posting ref) → matching debit</li>
 *   <li>ESSENCE: Essence postings no bank record explains. References whose debits and credits net
 *       to zero (a payment Finastra returned) are self-cancelling and ignored.</li>
 * </ul>
 * Exceptions: MISSING_IN_ESSENCE, AMOUNT_MISMATCH, MISSING_IN_SOURCE, with an owner queue.
 * Re-running a date replaces that date's results.
 */
@Component
public class ReconEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconEngine.class);

    private final JdbcClient jdbc;
    private final RestClient essence;
    private final RestClient orchestrator;
    private final RestClient ehi;

    private final String currency;

    public ReconEngine(JdbcClient jdbc, RestClient.Builder builder,
                       @Value("${pbp.services.essence-adapter-url}") String essenceUrl,
                       @Value("${pbp.services.orchestrator-url}") String orchestratorUrl,
                       @Value("${pbp.services.ehi-url}") String ehiUrl,
                       bank.pbp.common.market.MarketPack market) {
        this.currency = market.currency().getCurrencyCode();
        this.jdbc = jdbc;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(2000);
        f.setReadTimeout(30_000);
        this.essence = builder.clone().baseUrl(essenceUrl).requestFactory(f).build();
        this.orchestrator = builder.clone().baseUrl(orchestratorUrl).requestFactory(f).build();
        this.ehi = builder.clone().baseUrl(ehiUrl).requestFactory(f).build();
    }

    @Transactional
    public Map<String, Object> run(LocalDate date) {
        Correlation.set(Ids.newCorrelationId(JourneyType.REC));
        try {
            String d = date.toString();
            JsonNode postings = get(essence, "/v1/postings?date=" + d);
            JsonNode payments = get(orchestrator, "/v1/recon/payments?date=" + d);
            JsonNode presentments = get(ehi, "/v1/recon/presentments?date=" + d);

            Map<String, Totals> byRef = new HashMap<>();
            for (JsonNode p : postings) {
                byRef.computeIfAbsent(p.path("ref").asText(), k -> new Totals()).add(new BigDecimal(p.path("amount").asText()));
            }
            Set<String> explained = new HashSet<>();

            Result pay = new Result("PAYMENTS", "payments-ops");
            for (JsonNode p : payments) {
                String type = p.path("type").asText();
                boolean inbound = "INBOUND".equals(type);
                // Money in and Direct Debits carry Finastra's reference; payments we send carry our payment ID.
                boolean vendorRef = inbound || "DIRECT_DEBIT".equals(type);
                String ref = vendorRef ? p.path("finastraRef").asText() : p.path("paymentId").asText();
                // A fee (CHAPS) is posted under the payment's reference, so it is expected in the same debit total.
                BigDecimal amount = new BigDecimal(p.path("amount").asText())
                        .add(new BigDecimal(p.path("fee").asText("0")));
                Totals t = byRef.get(ref);
                explained.add(ref);
                if (t == null) {
                    pay.exception("MISSING_IN_ESSENCE", ref, amount);
                } else if ((inbound ? t.credits : t.debits).compareTo(amount) != 0) {
                    pay.exception("AMOUNT_MISMATCH", ref, amount);
                } else {
                    pay.match(ref, ref, amount, p.path("correlationId").asText(null));
                }
            }

            Result cards = new Result("CARDS", "cards-ops");
            for (JsonNode p : presentments) {
                String ref = p.path("postingRef").asText(null);
                BigDecimal amount = new BigDecimal(p.path("amount").asText());
                String source = p.path("messageId").asText();
                if (ref == null || ref.isBlank() || "null".equals(ref)) {
                    cards.exception("MISSING_IN_ESSENCE", source, amount);
                    continue;
                }
                explained.add(ref);
                Totals t = byRef.get(ref);
                if (t == null) {
                    cards.exception("MISSING_IN_ESSENCE", source, amount);
                } else if (t.debits.compareTo(amount) != 0) {
                    cards.exception("AMOUNT_MISMATCH", source, amount);
                } else {
                    cards.match(source, ref, amount, p.path("correlationId").asText(null));
                }
            }

            Result core = new Result("ESSENCE", "payments-ops");
            for (Map.Entry<String, Totals> e : byRef.entrySet()) {
                Totals t = e.getValue();
                if (explained.contains(e.getKey()) || t.credits.subtract(t.debits).signum() == 0) {
                    continue;
                }
                core.exception("MISSING_IN_SOURCE", e.getKey(), t.debits.max(t.credits));
            }
            core.lines = postings.size();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runDate", d);
            out.put("correlationId", Correlation.current().orElse(null));
            List<Map<String, Object>> runs = new ArrayList<>();
            for (Result r : List.of(pay, cards, core)) {
                runs.add(save(date, r));
            }
            out.put("runs", runs);
            log.info("recon.run date={} payments={}/{} cards={}/{} unexplainedPostings={}", d, pay.matches.size(),
                    pay.exceptions.size(), cards.matches.size(), cards.exceptions.size(), core.exceptions.size());
            return out;
        } finally {
            Correlation.clear();
        }
    }

    private Map<String, Object> save(LocalDate date, Result r) {
        jdbc.sql("DELETE FROM recon_match WHERE run_id IN (SELECT run_id FROM recon_run WHERE source = :s AND run_date = :d)")
                .param("s", r.source).param("d", date).update();
        jdbc.sql("DELETE FROM recon_exception WHERE run_id IN (SELECT run_id FROM recon_run WHERE source = :s AND run_date = :d)")
                .param("s", r.source).param("d", date).update();
        jdbc.sql("DELETE FROM recon_run WHERE source = :s AND run_date = :d").param("s", r.source).param("d", date).update();

        UUID runId = Ids.uuidV7();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int lines = r.lines > 0 ? r.lines : r.matches.size() + r.exceptions.size();
        jdbc.sql("""
                        INSERT INTO recon_run (run_id, source, run_date, lines_total, matched, exceptions, status,
                                               started_at, ended_at)
                        VALUES (:id, :source, :date, :lines, :matched, :exceptions, :status, :now, :now)""")
                .param("id", runId).param("source", r.source).param("date", date).param("lines", lines)
                .param("matched", r.matches.size()).param("exceptions", r.exceptions.size())
                .param("status", r.exceptions.isEmpty() ? "MATCHED" : "EXCEPTIONS").param("now", now)
                .update();
        for (String[] m : r.matches) {
            jdbc.sql("""
                            INSERT INTO recon_match (run_id, source_ref, target_ref, amount, currency, correlation_id, run_date)
                            VALUES (:run, :src, :tgt, :amount, :currency, :corr, :date)""")
                    .param("currency", currency).param("run", runId).param("src", m[0]).param("tgt", m[1]).param("amount", new BigDecimal(m[2]))
                    .param("corr", m[3]).param("date", date).update();
        }
        for (String[] x : r.exceptions) {
            jdbc.sql("""
                            INSERT INTO recon_exception (exception_id, run_id, type, source_ref, amount, owner_queue,
                                                         status, run_date)
                            VALUES (:id, :run, :type, :ref, :amount, :queue, 'OPEN', :date)""")
                    .param("id", Ids.uuidV7()).param("run", runId).param("type", x[0]).param("ref", x[1])
                    .param("amount", new BigDecimal(x[2])).param("queue", r.ownerQueue).param("date", date).update();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source", r.source);
        summary.put("matched", r.matches.size());
        summary.put("exceptions", r.exceptions.size());
        return summary;
    }

    private static JsonNode get(RestClient client, String uri) {
        return client.get().uri(uri).retrieve().body(JsonNode.class);
    }

    private static final class Totals {
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;

        void add(BigDecimal signed) {
            if (signed.signum() < 0) {
                debits = debits.add(signed.negate());
            } else {
                credits = credits.add(signed);
            }
        }
    }

    private static final class Result {
        final String source;
        final String ownerQueue;
        final List<String[]> matches = new ArrayList<>();
        final List<String[]> exceptions = new ArrayList<>();
        int lines;

        Result(String source, String ownerQueue) {
            this.source = source;
            this.ownerQueue = ownerQueue;
        }

        void match(String sourceRef, String targetRef, BigDecimal amount, String correlationId) {
            matches.add(new String[] {sourceRef, targetRef, amount.toPlainString(), correlationId});
        }

        void exception(String type, String ref, BigDecimal amount) {
            exceptions.add(new String[] {type, ref, amount.toPlainString()});
        }
    }
}
