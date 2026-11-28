package bank.pbp.essence;

import bank.pbp.common.error.PbpException;
import bank.pbp.common.market.AccountIdentifier;
import bank.pbp.common.market.MarketPack;
import bank.pbp.essence.gateway.EssenceGateway;
import bank.pbp.essence.gateway.EssenceGateway.Lane;
import bank.pbp.essence.gateway.EssenceGateway.PaymentInstruction;
import bank.pbp.essence.gateway.EssenceGateway.TransferInstruction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Internal Essence contract (LLD section 6). Translation only; no business decisions. */
@RestController
@RequestMapping("/v1")
public class EssenceController {

    private static final Logger log = LoggerFactory.getLogger(EssenceController.class);
    private static final String SCENARIO = "X-Sim-Scenario";

    private final EssenceGateway essence;
    private final MarketPack market;

    public EssenceController(EssenceGateway essence, MarketPack market) {
        this.essence = essence;
        this.market = market;
    }

    @PostMapping("/parties")
    @ResponseStatus(HttpStatus.CREATED)
    public EssenceGateway.Party createParty(@Valid @RequestBody NewParty req,
                                            @RequestHeader("Idempotency-Key") String key) {
        return timed("parties.create", () -> essence.createParty(req.name(), req.partyType(), key));
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public EssenceGateway.OpenedAccount openAccount(@Valid @RequestBody NewAccount req,
                                                    @RequestHeader("Idempotency-Key") String key) {
        return timed("accounts.open", () -> essence.openAccount(req.partyId(), req.name(),
                req.productCode() == null ? "UK-CURRENT-01" : req.productCode(), key));
    }

    /** {@code lane=ehi} uses the short timeout for card authorisations. */
    @GetMapping("/accounts/{accountId}/balance")
    public EssenceGateway.Balance balance(@PathVariable String accountId,
                                          @RequestParam(defaultValue = "standard") String lane,
                                          @RequestHeader(value = SCENARIO, required = false) String scenario) {
        Lane l = "ehi".equals(lane) ? Lane.EHI : Lane.STANDARD;
        return timed("accounts.balance." + lane, () -> essence.balance(accountId, l, scenario));
    }

    @PostMapping("/accounts/{accountId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public EssenceGateway.Hold placeHold(@PathVariable String accountId, @Valid @RequestBody NewHold req,
                                         @RequestHeader("Idempotency-Key") String key,
                                         @RequestHeader(value = SCENARIO, required = false) String scenario) {
        return timed("holds.place", () -> essence.placeHold(accountId, req.amount().value(), req.amount().currency(),
                req.reference(), key, scenario));
    }

    @PostMapping("/holds/{holdId}/capture")
    public Map<String, String> capture(@PathVariable String holdId, @RequestBody(required = false) Capture req) {
        String postingRef = timed("holds.capture",
                () -> essence.captureHold(holdId, req == null ? null : req.amount()));
        return Map.of("postingRef", postingRef);
    }

    @DeleteMapping("/holds/{holdId}")
    public Map<String, String> release(@PathVariable String holdId) {
        timed("holds.release", () -> {
            essence.releaseHold(holdId);
            return null;
        });
        return Map.of("holdId", holdId, "status", "RELEASED");
    }

    @PostMapping("/transfers")
    public Map<String, String> transfer(@Valid @RequestBody TransferRequest req,
                                        @RequestHeader(value = SCENARIO, required = false) String scenario) {
        if (req.creditor().sortCode() == null || req.creditor().accountNumber() == null) {
            throw new PbpException("GEN-VALID-001", 422, "Not supported",
                    "Same-bank transfers need the sort code and account number; other markets' accounts are not built yet");
        }
        String postingRef = timed("transfers.create", () -> essence.transfer(new TransferInstruction(
                req.debitAccount(), req.creditor().sortCode(), req.creditor().accountNumber(), req.amount().value(),
                req.amount().currency(), req.reference(), req.idempotencyKey()), scenario));
        return Map.of("postingRef", postingRef, "status", "POSTED");
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EssenceGateway.PaymentSubmission submitPayment(@Valid @RequestBody PaymentRequest req,
                                                          @RequestHeader(value = SCENARIO, required = false) String scenario) {
        String scheme = req.scheme() == null ? "FPS" : req.scheme();
        // The orchestrator chose the scheme; it must be one of this market's schemes to another bank.
        if ("INTERNAL".equals(scheme) || market.paymentScheme(scheme).isEmpty()) {
            throw new PbpException("GEN-VALID-001", 400, "Invalid request",
                    "scheme " + scheme + " is not a payment scheme in the '" + market.code() + "' market");
        }
        Map<String, Object> account = checkedAccount(req.creditor());
        return timed("payments.submit", () -> essence.submitPayment(new PaymentInstruction(req.debitAccount(),
                req.creditor().name(), req.creditor().sortCode(), req.creditor().accountNumber(), req.amount().value(),
                req.amount().currency(), req.reference(), req.endToEndId(), req.idempotencyKey(), scheme, account),
                scenario));
    }

    /** A creditor given as the market's account identifier ({scheme, fields}), checked by the pack; null in the UK shape. */
    private Map<String, Object> checkedAccount(Creditor creditor) {
        if (creditor.account() == null) {
            return null;
        }
        AccountIdentifier id = AccountIdentifier.fromJson(creditor.account());
        if (id == null) {
            throw new PbpException("GEN-VALID-001", 400, "Invalid request", "creditor.account needs a scheme and fields");
        }
        Map<String, String> problems = market.accountIdentifiers().problems(id);
        if (!problems.isEmpty()) {
            Map.Entry<String, String> first = problems.entrySet().iterator().next();
            throw new PbpException("GEN-VALID-001", 400, "Invalid request",
                    "creditor.account." + first.getKey() + " " + first.getValue());
        }
        return id.toJson();
    }

    /** Confirmation of Payee (LLD section 6: POST /v1/payees/confirm). */
    @PostMapping("/payees/confirm")
    public EssenceGateway.CopResult confirmPayee(@Valid @RequestBody CopRequest req,
                                                 @RequestHeader(value = SCENARIO, required = false) String scenario) {
        return timed("payees.confirm", () -> essence.confirmPayee(req.name(), req.sortCode(), req.accountNumber(), scenario));
    }

    @PostMapping("/accounts/{accountId}/fees")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> fee(@PathVariable String accountId, @Valid @RequestBody FeeRequest req,
                                   @RequestHeader("Idempotency-Key") String key) {
        String postingRef = timed("fees.charge", () -> essence.chargeFee(accountId, req.amount().value(),
                req.amount().currency(), req.reference(), key));
        return Map.of("postingRef", String.valueOf(postingRef));
    }

    @PostMapping("/direct-debits/collections/{ref}/pay")
    public Map<String, String> payCollection(@PathVariable String ref) {
        String postingRef = timed("directdebits.pay", () -> essence.payCollection(ref));
        return Map.of("collectionRef", ref, "status", "PAID", "postingRef", String.valueOf(postingRef));
    }

    @PostMapping("/direct-debits/collections/{ref}/return")
    public Map<String, String> returnCollection(@PathVariable String ref, @Valid @RequestBody ReasonRequest req) {
        timed("directdebits.return", () -> {
            essence.returnCollection(ref, req.reasonCode());
            return null;
        });
        return Map.of("collectionRef", ref, "status", "RETURNED", "reasonCode", req.reasonCode());
    }

    @PostMapping("/direct-debits/collections/{ref}/indemnity")
    public Map<String, String> indemnity(@PathVariable String ref, @Valid @RequestBody ReasonRequest req) {
        String claimRef = timed("directdebits.indemnity", () -> essence.indemnityClaim(ref, req.reasonCode()));
        return Map.of("collectionRef", ref, "status", "REFUNDED", "claimRef", String.valueOf(claimRef));
    }

    @PostMapping("/direct-debits/mandates/{ref}/cancel")
    public Map<String, String> cancelMandate(@PathVariable String ref, @Valid @RequestBody ReasonRequest req) {
        timed("directdebits.cancel", () -> {
            essence.cancelMandate(ref, req.reasonCode());
            return null;
        });
        return Map.of("mandateRef", ref, "status", "CANCELLED");
    }

    @GetMapping("/postings")
    public java.util.List<EssenceGateway.Posting> postings(@RequestParam String date) {
        return timed("postings.list", () -> essence.postings(date));
    }

    private static <T> T timed(String operation, java.util.function.Supplier<T> call) {
        long start = System.nanoTime();
        try {
            return call.get();
        } finally {
            log.info("vendor.call vendor=essence operation={} latencyMs={}", operation,
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    public record Money(@NotBlank @Pattern(regexp = "\\d{1,15}\\.\\d{2}") String value,
                        @NotBlank @bank.pbp.common.market.MarketCurrency String currency) {
    }

    /**
     * The payee: in the UK a sort code and account number (as before); in other markets the market's account
     * identifier, {scheme, fields}, checked against the pack.
     */
    public record Creditor(String name,
                           @Pattern(regexp = "\\d{6}") String sortCode,
                           @Pattern(regexp = "\\d{8}") String accountNumber,
                           Map<String, Object> account) {

        @AssertTrue(message = "needs a sortCode and accountNumber, or an account")
        public boolean isIdentified() {
            return (sortCode != null && accountNumber != null) || account != null;
        }
    }

    public record NewParty(@NotBlank String name, @NotBlank @Pattern(regexp = "INDIVIDUAL|BUSINESS") String partyType) {
    }

    public record NewAccount(@NotBlank String partyId, @NotBlank String name, String productCode) {
    }

    public record NewHold(@NotNull @Valid Money amount, String reference) {
    }

    public record Capture(String amount) {
    }

    public record TransferRequest(@NotBlank String debitAccount, @NotNull @Valid Creditor creditor,
                                  @NotNull @Valid Money amount, String reference, @NotBlank String idempotencyKey) {
    }

    public record PaymentRequest(@NotBlank String debitAccount, @NotNull @Valid Creditor creditor,
                                 @NotNull @Valid Money amount, String reference, @NotBlank String endToEndId,
                                 @NotBlank String idempotencyKey,
                                 @Pattern(regexp = "[A-Z_]{2,12}") String scheme) {
    }

    public record CopRequest(@NotBlank String name,
                             @NotBlank @Pattern(regexp = "\\d{6}") String sortCode,
                             @NotBlank @Pattern(regexp = "\\d{8}") String accountNumber) {
    }

    public record FeeRequest(@NotNull @Valid Money amount, @NotBlank String reference) {
    }

    public record ReasonRequest(@NotBlank @Pattern(regexp = "[0-9A-Z]{1,2}") String reasonCode) {
    }
}
