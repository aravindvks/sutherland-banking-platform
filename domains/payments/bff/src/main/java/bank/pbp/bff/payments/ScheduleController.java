package bank.pbp.bff.payments;

import bank.pbp.bff.IdempotencyStore;
import bank.pbp.bff.security.Caller;
import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.error.PbpException;
import bank.pbp.common.error.VendorErrors;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.client.RestClient;

/**
 * Standing orders and scheduled (future-dated) payments. Commands go to payment-commands
 * (schedule.create, schedule.cancel, schedule.run_now); the orchestrator runs each schedule as a
 * Temporal workflow and makes every payment through the normal payment saga.
 */
@RestController
@RequestMapping("/v1/schedules")
public class ScheduleController {

    private static final ZoneId UK = ZoneId.of("Europe/London");

    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final RestClient orchestrator;
    private final Caller caller;

    public ScheduleController(IdempotencyStore idempotency, EventPublisher events, Caller caller,
                              RestClient.Builder builder, @Value("${pbp.services.orchestrator-url}") String url) {
        this.idempotency = idempotency;
        this.events = events;
        this.caller = caller;
        this.orchestrator = builder.clone().baseUrl(url).build();
    }

    @GetMapping
    public JsonNode list(HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        return VendorErrors.call("orchestrator", "schedules.list", () -> orchestrator.get()
                .uri("/v1/customers/{id}/schedules", customerId).retrieve().body(JsonNode.class));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody NewSchedule body,
                                                      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                                      HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        LocalDate today = LocalDate.now(UK);
        LocalDate start = LocalDate.parse(body.startDate());
        if (start.isBefore(today)) {
            throw new PbpException("GEN-VALID-001", 400, "Request is not valid", "startDate cannot be in the past");
        }
        if ("STANDING_ORDER".equals(body.kind()) && body.frequency() == null) {
            throw new PbpException("GEN-VALID-001", 400, "Request is not valid", "A standing order needs a frequency");
        }
        if (body.endDate() != null && !body.endDate().isBlank() && LocalDate.parse(body.endDate()).isBefore(start)) {
            throw new PbpException("GEN-VALID-001", 400, "Request is not valid", "endDate is before startDate");
        }
        String correlationId = Ids.newCorrelationId(JourneyType.PAY);
        Correlation.set(correlationId);
        Map<String, Object> response = idempotency.once(customerId + ":" + idempotencyKey, body, () -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scheduleId", Ids.uuidV7().toString());
            r.put("status", "ACCEPTED");
            r.put("correlationId", correlationId);
            return r;
        }, r -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scheduleId", r.get("scheduleId"));
            data.put("kind", body.kind());
            data.put("frequency", "ONE_OFF".equals(body.kind()) ? null : body.frequency());
            data.put("startDate", body.startDate());
            data.put("endDate", body.endDate());
            data.put("debtorAccount", body.debtorAccount());
            data.put("creditor", Map.of("name", body.creditor().name(), "sortCode", body.creditor().sortCode(),
                    "accountNumber", body.creditor().accountNumber()));
            data.put("amount", Map.of("value", body.amount().value(), "currency", body.amount().currency()));
            data.put("reference", body.reference());
            data.put("scheme", body.scheme());
            data.put("copResult", body.copResult());
            events.publish(Topics.PAYMENT_COMMANDS, EventEnvelope.of("schedule.create", "bff",
                    String.valueOf(r.get("correlationId")), null, customerId.toString(), data));
        });
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(Correlation.HEADER, String.valueOf(response.get("correlationId")))
                .body(response);
    }

    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID scheduleId, HttpServletRequest request) {
        return command("schedule.cancel", scheduleId, request);
    }

    /** Demo only: make the next payment now rather than on its due date. */
    @PostMapping("/{scheduleId}/run-now")
    public ResponseEntity<Map<String, Object>> runNow(@PathVariable UUID scheduleId, HttpServletRequest request) {
        return command("schedule.run_now", scheduleId, request);
    }

    private ResponseEntity<Map<String, Object>> command(String type, UUID scheduleId, HttpServletRequest request) {
        UUID customerId = caller.customerId(request);
        String correlationId = Correlation.current().orElseGet(() -> Ids.newCorrelationId(JourneyType.PAY));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scheduleId", scheduleId.toString());
        events.publish(Topics.PAYMENT_COMMANDS,
                EventEnvelope.of(type, "bff", correlationId, null, customerId.toString(), data));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scheduleId", scheduleId.toString());
        out.put("status", "ACCEPTED");
        out.put("correlationId", correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
    }

    public record NewSchedule(
            @NotBlank @Pattern(regexp = "ONE_OFF|STANDING_ORDER") String kind,
            @Pattern(regexp = "WEEKLY|MONTHLY") String frequency,
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String startDate,
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String endDate,
            @NotBlank String debtorAccount,
            @NotNull @Valid PaymentRequest.Creditor creditor,
            @NotNull @Valid PaymentRequest.Money amount,
            @Size(max = 18) String reference,
            @Pattern(regexp = "FPS|CHAPS") String scheme,
            @Pattern(regexp = "MATCH|CLOSE_MATCH|NO_MATCH|UNAVAILABLE") String copResult) {
    }
}
