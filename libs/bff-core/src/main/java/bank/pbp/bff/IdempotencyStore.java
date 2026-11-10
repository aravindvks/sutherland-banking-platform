package bank.pbp.bff;

import bank.pbp.common.error.PbpException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Duplicate protection on Idempotency-Key (LLD sections 1 and 10). The first request stores its
 * response; a retry with the same body gets the same response; the same key with a different
 * body is refused with GEN-IDEMP-001.
 */
@Component
public class IdempotencyStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public IdempotencyStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Runs {@code action} once per key. {@code action} sees the stored response and publishes the
     * command; if it throws, the key is rolled back so the client can retry.
     */
    @Transactional
    public Map<String, Object> once(String key, Object request, Supplier<Map<String, Object>> response,
                                    Consumer<Map<String, Object>> action) {
        String hash = sha256(toJson(request));
        Optional<Map<String, Object>> replay = replay(key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Map<String, Object> body = response.get();
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_key (key, request_hash, response)
                        VALUES (:key, :hash, CAST(:response AS jsonb))
                        ON CONFLICT (key) DO NOTHING""")
                .param("key", key).param("hash", hash).param("response", toJson(body))
                .update();
        if (inserted == 0) {
            return replay(key, hash).orElseThrow();
        }
        action.accept(body);
        return body;
    }

    private Optional<Map<String, Object>> replay(String key, String hash) {
        return jdbc.sql("SELECT request_hash, response::text AS response FROM idempotency_key WHERE key = :key")
                .param("key", key)
                .query((rs, n) -> {
                    if (!hash.equals(rs.getString("request_hash"))) {
                        throw new PbpException("GEN-IDEMP-001", 409, "Idempotency key reused",
                                "This Idempotency-Key was already used with a different request body");
                    }
                    return fromJson(rs.getString("response"));
                })
                .optional();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
