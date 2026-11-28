package bank.pbp.market.us;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.PaymentIntent;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UsPaymentRouterTest {

    private static final String OWN = "026009593";
    private static final String NOT_ON_RTP = "021000021";
    private static final String ON_NEITHER = "011000015";
    private static final String ON_BOTH = "121000248";

    private final UsMarketPack us = new UsMarketPack(new BigDecimal("25000.00"), new BigDecimal("10000.00"),
            new BigDecimal("25000.00"), new BigDecimal("1000000.00"), new BigDecimal("25.00"), LocalTime.of(9, 0),
            LocalTime.of(17, 0), Set.of(), new BigDecimal("5000.00"), Set.of(NOT_ON_RTP, ON_NEITHER), Set.of(ON_NEITHER));

    private String route(String routing, String chosen, String amount) {
        return us.router().route(new PaymentIntent(OWN, UsAbaScheme.of(routing, "000123456789", "CHECKING"), chosen,
                amount == null ? null : new BigDecimal(amount))).code();
    }

    @Test
    void sameBankIsInternalWhateverTheCustomerChose() {
        assertThat(route(OWN, null, "10.00")).isEqualTo("INTERNAL");
        assertThat(route(OWN, "WIRE", "50000.00")).isEqualTo("INTERNAL");
    }

    @Test
    void instantByRtpThenFedNowThenAch() {
        assertThat(route(ON_BOTH, null, "100.00")).isEqualTo("RTP");
        assertThat(route(ON_BOTH, "RTP", "100.00")).isEqualTo("RTP");
        assertThat(route(NOT_ON_RTP, null, "100.00")).isEqualTo("FEDNOW");
        assertThat(route(NOT_ON_RTP, "RTP", "100.00")).isEqualTo("FEDNOW");
        assertThat(route(ON_NEITHER, null, "100.00")).isEqualTo("ACH");
    }

    @Test
    void customerChoiceOfAchOrWire() {
        assertThat(route(ON_BOTH, "ACH", "100.00")).isEqualTo("ACH");
        assertThat(route(ON_BOTH, "WIRE", "100.00")).isEqualTo("WIRE");
    }

    @Test
    void aboveTheInstantLimitGoesByWire() {
        assertThat(route(ON_BOTH, null, "25000.00")).isEqualTo("RTP");
        assertThat(route(ON_BOTH, null, "25000.01")).isEqualTo("WIRE");
        assertThat(route(ON_NEITHER, null, "25000.01")).isEqualTo("WIRE");
    }

    @Test
    void unknownAmountOrPayeeStillRoutes() {
        assertThat(route(ON_BOTH, null, null)).isEqualTo("RTP");
        assertThat(us.router().route(new PaymentIntent(OWN, null, null, null)).code()).isEqualTo("ACH");
    }
}
