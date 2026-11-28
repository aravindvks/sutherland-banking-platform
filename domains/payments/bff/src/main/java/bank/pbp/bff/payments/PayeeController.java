package bank.pbp.bff.payments;

import bank.pbp.bff.security.Caller;
import bank.pbp.common.error.VendorErrors;
import bank.pbp.common.ids.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Payees and Confirmation of Payee (LLD section 2). The check goes to the Essence adapter; the
 * result and time are stored on the payee and sent with the payment as copResult. What a result
 * means for a payment is decided by the orchestrator, which checks again when it pays.
 */
@RestController
@RequestMapping("/v1/payees")
public class PayeeController {

    private final RestClient essence;
    private final JdbcClient jdbc;
    private final Caller caller;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public PayeeController(RestClient.Builder builder, JdbcClient jdbc, Caller caller,
                           @Value("${pbp.services.essence-adapter-url}") String essenceUrl,
                           com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.essence = builder.clone().baseUrl(essenceUrl).build();
        this.jdbc = jdbc;
        this.caller = caller;
        this.mapper = mapper;
    }

    /** Checks a name against an account without saving it. */
    @PostMapping("/confirm")
    public JsonNode confirm(@Valid @RequestBody PayeeDetails body, HttpServletRequest request) {
        caller.customerId(request);
        return check(body);
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        return jdbc.sql("""
                        SELECT payee_id::text AS "payeeId", name, sort_code AS "sortCode",
                               account_number AS "accountNumber", reference,
                               COALESCE(name_check_result, cop_result) AS "copResult",
                               cop_checked_at::text AS "copCheckedAt", account_identifier::text AS "account"
                        FROM payee WHERE customer_id = :c ORDER BY created_at DESC LIMIT 50""")
                .param("c", customerId).query().listOfRows();
    }

    /** Saves a payee after checking the name; a payee saved again is checked again. */
    @PostMapping
    public Map<String, Object> save(@Valid @RequestBody PayeeDetails body, HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        JsonNode cop = check(body);
        String result = cop.path("result").asText("UNAVAILABLE");
        String id = jdbc.sql("""
                        INSERT INTO payee (payee_id, customer_id, name, sort_code, account_number, reference,
                                           cop_result, name_check_result, account_identifier, cop_checked_at)
                        VALUES (:id, :c, :name, :sort, :acc, :ref, :cop, :cop, CAST(:account AS jsonb), now())
                        ON CONFLICT (customer_id, sort_code, account_number)
                        DO UPDATE SET name = EXCLUDED.name, reference = EXCLUDED.reference,
                                      cop_result = EXCLUDED.cop_result, name_check_result = EXCLUDED.name_check_result,
                                      account_identifier = EXCLUDED.account_identifier, cop_checked_at = now()
                        RETURNING payee_id::text""")
                .param("id", Ids.uuidV7())
                .param("c", customerId)
                .param("name", body.name())
                .param("sort", body.effectiveSortCode())
                .param("acc", body.effectiveAccountNumber())
                .param("ref", body.reference())
                .param("cop", result)
                .param("account", accountJson(body))
                .query(String.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("payeeId", id);
        out.put("name", body.name());
        out.put("sortCode", body.effectiveSortCode());
        out.put("accountNumber", body.effectiveAccountNumber());
        out.put("reference", body.reference());
        out.put("copResult", result);
        out.put("matchedName", cop.path("matchedName").asText(null));
        return out;
    }

    /** The payee's account as the market's identifier, stored beside the sort code and number (Stage 1 step 4). */
    private String accountJson(PayeeDetails body) {
        try {
            return mapper.writeValueAsString(body.identifier().toJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode check(PayeeDetails body) {
        if (body.effectiveSortCode() == null || body.effectiveAccountNumber() == null) {
            // Saved payees and the name check are UK-shaped (sort code and account number) until other markets'
            // payee storage and name checks are built (docs/design/market-packs.md, 4.3).
            throw new bank.pbp.common.error.PbpException("GEN-VALID-001", 422, "Not supported",
                    "Payees and the name check are not built for this market yet");
        }
        Map<String, Object> req = Map.of("name", body.name(), "sortCode", body.effectiveSortCode(),
                "accountNumber", body.effectiveAccountNumber());
        return VendorErrors.call("essence", "payees.confirm", () -> essence.post().uri("/v1/payees/confirm")
                .body(req).retrieve().body(JsonNode.class));
    }

    /** A payee to check or save; the account in either shape ({@link PayeeAccount}). */
    @ValidAccount
    public record PayeeDetails(@NotBlank @Size(max = 140) String name,
                               String sortCode,
                               String accountNumber,
                               @Size(max = 18) String reference,
                               @jakarta.validation.Valid
                               @com.fasterxml.jackson.annotation.JsonInclude(
                                       com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) AccountBody account)
            implements PayeeAccount {
    }
}
