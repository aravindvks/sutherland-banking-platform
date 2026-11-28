package bank.pbp.market.jm;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.PaymentIntent;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JmPaymentRouterTest {

    private final JmMarketPack jm = new JmMarketPack(new BigDecimal("3000000.00"), new BigDecimal("1500000.00"),
            new BigDecimal("1000000.00"), new BigDecimal("50000000.00"), new BigDecimal("250.00"), LocalTime.of(8, 30),
            LocalTime.of(13, 0), Set.of(), new BigDecimal("750000.00"),
            new JmAccountScheme("\\d{2,4}", "\\d{1,5}", "\\d{4,20}"));

    private String route(String bank, String chosen, String amount) {
        return jm.router().route(new PaymentIntent("77", JmAccountScheme.of(bank, "10101", "123456789"), chosen,
                amount == null ? null : new BigDecimal(amount))).code();
    }

    @Test
    void sameBankIsInternal() {
        assertThat(route("77", null, "5000000.00")).isEqualTo("INTERNAL");
    }

    @Test
    void belowTheThresholdByAchUnlessTheCustomerChoseRtgs() {
        assertThat(route("12", null, "999999.99")).isEqualTo("ACH");
        assertThat(route("12", "RTGS", "5000.00")).isEqualTo("RTGS");
        assertThat(route("12", "ACH", "5000.00")).isEqualTo("ACH");
    }

    @Test
    void oneMillionDollarsAndAboveGoByRtgsWhateverWasChosen() {
        assertThat(route("12", null, "1000000.00")).isEqualTo("RTGS");
        assertThat(route("12", "ACH", "2500000.00")).isEqualTo("RTGS");
    }

    @Test
    void achNeverTakesTheThreshold() {
        assertThat(jm.paymentScheme("ACH").orElseThrow().allows(new BigDecimal("999999.99"))).isTrue();
        assertThat(jm.paymentScheme("ACH").orElseThrow().allows(new BigDecimal("1000000.00"))).isFalse();
    }
}
