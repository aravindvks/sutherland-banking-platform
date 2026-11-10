package bank.pbp.bff.security;

import bank.pbp.common.error.PbpException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is calling. With login on: the signed-in user's name from the token, and the bank customer
 * linked to that login. With login off (IDE runs): the development headers.
 */
@Component
public class Caller {

    private final boolean authEnabled;
    private final JdbcClient jdbc;

    public Caller(@Value("${pbp.auth.enabled:false}") boolean authEnabled, JdbcClient jdbc) {
        this.authEnabled = authEnabled;
        this.jdbc = jdbc;
    }

    public boolean authEnabled() {
        return authEnabled;
    }

    /** The signed-in user's name (staff reviewer, or customer login). */
    public String username(HttpServletRequest request) {
        if (authEnabled) {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            return a == null ? "anonymous" : a.getName();
        }
        String staff = request.getHeader("X-Staff-User");
        return staff == null ? "dev-user" : staff;
    }

    /** The bank customer behind this call. */
    public UUID customerId(HttpServletRequest request) {
        if (!authEnabled) {
            String header = request.getHeader("X-Customer-Id");
            if (header == null) {
                header = request.getParameter("customerId");
            }
            if (header == null) {
                throw new PbpException("GEN-HEADER-001", 400, "Required header missing", "Header X-Customer-Id is required");
            }
            return UUID.fromString(header);
        }
        String username = username(request);
        return linkedCustomer(username).orElseThrow(() -> new PbpException("CUS-LINK-001", 404,
                "No bank customer for this login", "Login " + username + " is not linked to a bank customer yet"));
    }

    public Optional<UUID> linkedCustomer(String username) {
        return jdbc.sql("SELECT customer_id FROM customer_login WHERE username = :u")
                .param("u", username).query(UUID.class).optional();
    }

    public void link(String username, UUID customerId, String linkedBy) {
        jdbc.sql("""
                        INSERT INTO customer_login (username, customer_id, linked_by) VALUES (:u, :c, :by)
                        ON CONFLICT (username) DO UPDATE SET customer_id = :c, linked_by = :by, linked_at = now()""")
                .param("u", username).param("c", customerId).param("by", linkedBy).update();
    }
}
