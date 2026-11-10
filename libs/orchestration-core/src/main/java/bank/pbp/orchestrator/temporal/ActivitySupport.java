package bank.pbp.orchestrator.temporal;

import bank.pbp.common.correlation.Correlation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import java.util.function.Supplier;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Shared rules for activities that call internal services:
 * the correlation ID is restored on the Temporal thread, and HTTP failures become Temporal
 * failures whose retry behaviour follows the LLD problem envelope's {@code retryable} flag.
 */
public final class ActivitySupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ActivitySupport() {
    }

    public static <T> T withCorrelation(String correlationId, Supplier<T> work) {
        Correlation.set(correlationId);
        try {
            return work.get();
        } finally {
            Correlation.clear();
        }
    }

    public static <T> T call(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            JsonNode problem = parse(e.getResponseBodyAsString());
            String code = problem.path("code").asText("GEN-HTTP-" + e.getStatusCode().value());
            String detail = operation + ": " + problem.path("detail").asText(e.getStatusText());
            boolean retryable = problem.path("retryable").asBoolean(e.getStatusCode().is5xxServerError());
            throw retryable
                    ? ApplicationFailure.newFailure(detail, code)
                    : ApplicationFailure.newNonRetryableFailure(detail, code);
        } catch (ResourceAccessException e) {
            throw ApplicationFailure.newFailure(operation + ": " + e.getMessage(), "VND-TIMEOUT-001");
        }
    }

    private static JsonNode parse(String body) {
        try {
            return MAPPER.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
