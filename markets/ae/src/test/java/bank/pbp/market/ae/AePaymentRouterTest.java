package bank.pbp.market.ae;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.PaymentIntent;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AePaymentRouterTest {

    private static final String OWN = "033";
    private static final String OTHER = "AE420260001015333439101";     // bank 026
    private static final String OFF_AANI = "AE750350000000123456789";  // bank 035

    private final AeMarketPack ae = new AeMarketPack(new BigDecimal("100000.00"), new BigDecimal("50000.00"),
            new BigDecimal("10000.00"), BigDecimal.ZERO, new BigDecimal("5000000.00"), BigDecimal.ZERO,
            LocalTime.of(8, 0), LocalTime.of(16, 0), Set.of(), new BigDecimal("20000.00"), Set.of("035"));

    private String route(String iban, String chosen, String amount) {
        return ae.router().route(new PaymentIntent(OWN, AeIbanScheme.of(iban), chosen,
                amount == null ? null : new BigDecimal(amount))).code();
    }

    @Test
    void sameBankByTheIbansBankCode() {
        assertThat(route("AE070331234567890123456", "UAEFTS", "200000.00")).isEqualTo("INTERNAL");
    }

    @Test
    void aaniWithinItsLimitOtherwiseUaefts() {
        assertThat(route(OTHER, null, "10000.00")).isEqualTo("AANI");
        assertThat(route(OTHER, null, "10000.01")).isEqualTo("UAEFTS");
        assertThat(route(OFF_AANI, null, "100.00")).isEqualTo("UAEFTS");
        assertThat(route(OTHER, "UAEFTS", "100.00")).isEqualTo("UAEFTS");
    }
}
