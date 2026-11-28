package bank.pbp.market.co;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.PaymentIntent;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoPaymentRouterTest {

    private final CoMarketPack co = new CoMarketPack(new BigDecimal("50000000.00"), new BigDecimal("20000000.00"),
            new BigDecimal("5000000.00"), new BigDecimal("12110000.00"), new BigDecimal("2000000000.00"),
            BigDecimal.ZERO, LocalTime.of(8, 0), LocalTime.of(15, 0), Set.of(), new BigDecimal("5000000.00"),
            Set.of("1099"), new CoAccountScheme("\\d{3,4}", "\\d{6,20}"));

    private String route(String bank, String chosen, String amount) {
        return co.router().route(new PaymentIntent("1001", CoAccountScheme.of(bank, "123456789", "SAVINGS"), chosen,
                amount == null ? null : new BigDecimal(amount))).code();
    }

    @Test
    void sameBankIsInternal() {
        assertThat(route("1001", "CUD", "90000000.00")).isEqualTo("INTERNAL");
    }

    @Test
    void breBByDefaultWithinTheBanksLimit() {
        assertThat(route("1007", null, "5000000.00")).isEqualTo("BREB");
        assertThat(route("1007", null, "5000000.01")).isEqualTo("ACH");
        assertThat(route("1099", null, "100.00")).isEqualTo("ACH");
    }

    @Test
    void customerChoiceOfAchOrCud() {
        assertThat(route("1007", "ACH", "100.00")).isEqualTo("ACH");
        assertThat(route("1007", "CUD", "100.00")).isEqualTo("CUD");
    }
}
