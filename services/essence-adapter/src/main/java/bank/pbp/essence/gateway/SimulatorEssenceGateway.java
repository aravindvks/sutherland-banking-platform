package bank.pbp.essence.gateway;

import bank.pbp.common.error.VendorErrors;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Connector for the vendor simulator (dev, CI and demos). */
@Component
@ConditionalOnProperty(name = "vendor.essence.mode", havingValue = "simulator", matchIfMissing = true)
public class SimulatorEssenceGateway implements EssenceGateway {

    private static final String SCENARIO = "X-Sim-Scenario";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient standard;
    private final RestClient ehi;

    public SimulatorEssenceGateway(RestClient.Builder builder,
                                   @Value("${vendor.essence.base-url}") String baseUrl,
                                   @Value("${vendor.essence.timeout.connect-ms}") int connectMs,
                                   @Value("${vendor.essence.timeout.standard-ms}") int standardMs,
                                   @Value("${vendor.essence.timeout.ehi-ms}") int ehiMs) {
        this.standard = builder.clone().baseUrl(baseUrl).requestFactory(factory(connectMs, standardMs)).build();
        this.ehi = builder.clone().baseUrl(baseUrl).requestFactory(factory(connectMs, ehiMs)).build();
    }

    @Override
    public Party createParty(String name, String partyType, String idempotencyKey) {
        Map<String, Object> r = post(standard, "parties.create", "/parties",
                Map.of("name", name, "type", partyType, "clientRequestId", idempotencyKey), null);
        return new Party(str(r.get("partyId")));
    }

    @Override
    public OpenedAccount openAccount(String partyId, String name, String productCode, String idempotencyKey) {
        Map<String, Object> r = post(standard, "accounts.open", "/accounts", Map.of("partyId", partyId, "name", name,
                "productCode", productCode, "clientRequestId", idempotencyKey), null);
        return new OpenedAccount(str(r.get("accountId")), str(r.get("sortCode")), str(r.get("accountNumber")));
    }

    @Override
    public Balance balance(String accountId, Lane lane, String scenario) {
        RestClient client = lane == Lane.EHI ? ehi : standard;
        Map<String, Object> r = VendorErrors.call("essence", "accounts.balance", () -> client.get()
                .uri("/accounts/{id}/balance", accountId)
                .headers(h -> {
                    if (scenario != null) {
                        h.set(SCENARIO, scenario);
                    }
                })
                .retrieve()
                .body(MAP));
        return new Balance(accountId, str(r.get("available")), str(r.get("ledger")), str(r.get("currency")));
    }

    @Override
    public Hold placeHold(String accountId, String amount, String currency, String reference, String idempotencyKey,
                          String scenario) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("reference", reference);
        body.put("clientRequestId", idempotencyKey);
        Map<String, Object> r = post(ehi, "holds.place", "/accounts/" + accountId + "/holds", body, scenario);
        return new Hold(str(r.get("holdId")), str(r.get("available")));
    }

    @Override
    public String captureHold(String holdId, String amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        return str(post(ehi, "holds.capture", "/holds/" + holdId + "/capture", body, null).get("postingRef"));
    }

    @Override
    public void releaseHold(String holdId) {
        VendorErrors.call("essence", "holds.release", () -> ehi.delete().uri("/holds/{id}", holdId)
                .retrieve().toBodilessEntity());
    }

    @Override
    public String transfer(TransferInstruction t, String scenario) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debitAccountId", t.debitAccount());
        body.put("creditSortCode", t.creditorSortCode());
        body.put("creditAccountNumber", t.creditorAccountNumber());
        body.put("amount", t.amount());
        body.put("currency", t.currency());
        body.put("reference", t.reference() == null ? "" : t.reference());
        body.put("clientRequestId", t.idempotencyKey());
        return str(post(standard, "transfers.create", "/transfers", body, scenario).get("postingRef"));
    }

    @Override
    public PaymentSubmission submitPayment(PaymentInstruction p, String scenario) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debitAccountId", p.debitAccount());
        body.put("creditorName", p.creditorName());
        body.put("creditorSortCode", p.creditorSortCode());
        body.put("creditorAccountNumber", p.creditorAccountNumber());
        body.put("amount", p.amount());
        body.put("currency", p.currency());
        body.put("reference", p.reference() == null ? "" : p.reference());
        body.put("endToEndId", p.endToEndId());
        body.put("clientRequestId", p.idempotencyKey());
        body.put("scheme", p.scheme() == null ? "FPS" : p.scheme());
        if (p.creditorAccount() != null) {
            body.put("creditorAccount", p.creditorAccount());
        }
        Map<String, Object> r = post(standard, "payments.submit", "/payments", body, scenario);
        return new PaymentSubmission(str(r.get("finastraRef")), str(r.get("status")), str(r.get("scheme")));
    }

    @Override
    public CopResult confirmPayee(String name, String sortCode, String accountNumber, String scenario) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("sortCode", sortCode);
        body.put("accountNumber", accountNumber);
        Map<String, Object> r = post(standard, "payees.confirm", "/cop", body, scenario);
        return new CopResult(str(r.get("result")), str(r.get("matchedName")), str(r.get("reasonCode")));
    }

    @Override
    public String chargeFee(String accountId, String amount, String currency, String reference, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("reference", reference);
        body.put("clientRequestId", idempotencyKey);
        return str(post(standard, "fees.charge", "/accounts/" + accountId + "/fees", body, null).get("postingRef"));
    }

    @Override
    public String payCollection(String collectionRef) {
        return str(post(standard, "directdebits.pay", "/direct-debits/collections/" + collectionRef + "/pay",
                new LinkedHashMap<>(), null).get("postingRef"));
    }

    @Override
    public void returnCollection(String collectionRef, String reasonCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", reasonCode);
        post(standard, "directdebits.return", "/direct-debits/collections/" + collectionRef + "/return", body, null);
    }

    @Override
    public String indemnityClaim(String collectionRef, String reasonCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", reasonCode);
        return str(post(standard, "directdebits.indemnity", "/direct-debits/collections/" + collectionRef + "/indemnity",
                body, null).get("claimRef"));
    }

    @Override
    public void cancelMandate(String mandateRef, String reasonCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", reasonCode);
        post(standard, "directdebits.cancel", "/direct-debits/mandates/" + mandateRef + "/cancel", body, null);
    }

    @Override
    public java.util.List<Posting> postings(String date) {
        java.util.List<Map<String, Object>> rows = VendorErrors.call("essence", "postings.list", () -> standard.get()
                .uri(u -> u.path("/postings").queryParam("date", date).build())
                .retrieve()
                .body(new ParameterizedTypeReference<java.util.List<Map<String, Object>>>() {
                }));
        return rows.stream().map(r -> new Posting(str(r.get("postingId")), str(r.get("date")), str(r.get("accountId")),
                str(r.get("amount")), str(r.get("ref")), str(r.get("type")))).toList();
    }

    private static Map<String, Object> post(RestClient client, String operation, String path,
                                            Map<String, Object> body, String scenario) {
        return VendorErrors.call("essence", operation, () -> client.post()
                .uri(path)
                .headers(h -> {
                    if (scenario != null) {
                        h.set(SCENARIO, scenario);
                    }
                })
                .body(body)
                .retrieve()
                .body(MAP));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static SimpleClientHttpRequestFactory factory(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return f;
    }
}
