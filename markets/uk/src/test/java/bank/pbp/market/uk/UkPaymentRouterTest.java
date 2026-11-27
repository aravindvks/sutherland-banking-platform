package bank.pbp.market.uk;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.AccountIdentifier;
import bank.pbp.common.market.PaymentIntent;
import bank.pbp.common.market.PaymentRouter;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The UK router chooses exactly as PaymentCommandListener.paymentType did before Stage 1 step 5. */
class UkPaymentRouterTest {

    private static final String BANK = "040004";

    private final PaymentRouter router = new UkMarketPack(new BigDecimal("25000.00"), new BigDecimal("10000.00"),
            new BigDecimal("1000000.00"), new BigDecimal("20.00"), LocalTime.of(6, 0), LocalTime.of(17, 30),
            Set.of(), new BigDecimal("5000.00")).router();

    /** The orchestrator's rule before step 5, kept here as the reference. */
    private static String before(String bankSortCode, String creditorSortCode, String requestedScheme) {
        if (bankSortCode.equals(creditorSortCode)) {
            return "INTERNAL";
        }
        return "CHAPS".equals(requestedScheme) ? "CHAPS" : "FPS";
    }

    private String route(String sortCode, String accountNumber, String scheme, String amount) {
        return router.route(new PaymentIntent(BANK, AccountIdentifier.fromOriginalShape(sortCode, accountNumber),
                scheme, amount == null ? null : new BigDecimal(amount))).code();
    }

    @Test
    void sameBankIsInternalWhateverTheCustomerChose() {
        assertThat(route(BANK, "12345678", null, "10.00")).isEqualTo("INTERNAL");
        assertThat(route(BANK, "12345678", "CHAPS", "50000.00")).isEqualTo("INTERNAL");
    }

    @Test
    void otherBanksGoByFasterPaymentsUnlessCustomerChoseChaps() {
        assertThat(route("200000", "12345678", null, "10.00")).isEqualTo("FPS");
        assertThat(route("200000", "12345678", "FPS", "10.00")).isEqualTo("FPS");
        assertThat(route("200000", "12345678", "CHAPS", "10.00")).isEqualTo("CHAPS");
    }

    @Test
    void amountAboveTheFasterPaymentsLimitStaysOnFasterPayments() {
        // Today the limit check rejects it (PAY-LIMIT-001); routing does not move it to CHAPS.
        assertThat(route("200000", "12345678", null, "25000.01")).isEqualTo("FPS");
    }

    @Test
    void routesExactlyAsBefore() {
        String[] sortCodes = {BANK, "200000", "040005", null, ""};
        String[] schemes = {null, "", "FPS", "CHAPS", "chaps", "BACS", "INTERNAL"};
        for (String sortCode : sortCodes) {
            for (String scheme : schemes) {
                for (String accountNumber : Arrays.asList("12345678", null)) {
                    assertThat(route(sortCode, accountNumber, scheme, "100.00"))
                            .as("sortCode=%s scheme=%s accountNumber=%s", sortCode, scheme, accountNumber)
                            .isEqualTo(before(BANK, sortCode, scheme));
                }
            }
        }
    }

    @Test
    void noPayeeAccountRoutesToAnotherBank() {
        assertThat(router.route(new PaymentIntent(BANK, null, null, null)).code()).isEqualTo("FPS");
    }

    @Test
    void routedSchemesAreThePacksOwn() {
        assertThat(router.route(new PaymentIntent(BANK, null, "CHAPS", null)).fee()).isEqualByComparingTo("20.00");
    }
}
