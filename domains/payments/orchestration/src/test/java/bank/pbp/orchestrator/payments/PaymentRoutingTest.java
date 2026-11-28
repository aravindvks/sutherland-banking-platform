package bank.pbp.orchestrator.payments;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.common.market.AccountIdentifier;
import bank.pbp.common.market.MarketPack;
import bank.pbp.market.uk.UkMarketPack;
import bank.pbp.orchestrator.payments.PaymentActivities.RiskResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Market packs, Stage 1 step 5: the scheme comes from the UK pack's router, and the limit and fee from the routed
 * scheme. Values are the orchestrator's defaults (application.yml); results must match what it gave before.
 */
class PaymentRoutingTest {

    private static final String BANK = "040004";

    private final MarketPack uk = new UkMarketPack(new BigDecimal("25000.00"), new BigDecimal("10000.00"),
            new BigDecimal("1000000.00"), new BigDecimal("20.00"), LocalTime.of(6, 0), LocalTime.of(17, 30),
            Set.of(), new BigDecimal("5000.00"));

    // riskCheck reads no database, Kafka or adapter, so those stay unset.
    private final PaymentActivitiesImpl activities = new PaymentActivitiesImpl(null, null, null, new ObjectMapper(),
            RestClient.builder(), "http://essence-adapter.invalid", uk);

    private String route(String sortCode, String scheme, String amount) {
        return PaymentCommandListener.paymentType(uk, BANK, AccountIdentifier.fromOriginalShape(sortCode, "41550198"),
                scheme, amount);
    }

    private RiskResult risk(String type, String amount) {
        return activities.riskCheck(new PaymentCommand("0192f1c4-0000-7000-8000-0000000000aa",
                "0192f1c4-0000-7000-8000-000000000001", "COR-PAY-7K2M9QXA4B1D", "ACC-5501", "Ravi Kumar", "200001",
                "41550198", amount, "GBP", "Rent share", type, "key-1", "2026-11-06T10:00:00Z", null, null, null));
    }

    @Test
    void routesAsBefore() {
        assertThat(route(BANK, "CHAPS", "10.00")).isEqualTo("INTERNAL");
        assertThat(route("200001", null, "10.00")).isEqualTo("FPS");
        assertThat(route("200001", "CHAPS", "10.00")).isEqualTo("CHAPS");
        assertThat(route("200001", null, "not a number")).isEqualTo("FPS");
        assertThat(PaymentCommandListener.paymentType(uk, BANK, null, null, null)).isEqualTo("FPS");
    }

    @Test
    void fasterPaymentsAndSameBankShareTheSinglePaymentLimitAndAreFree() {
        assertThat(risk("FPS", "25000.00")).isEqualTo(RiskResult.pass(true, "0.00"));
        assertThat(risk("INTERNAL", "9999.99")).isEqualTo(RiskResult.pass(false, "0.00"));
        assertThat(risk("FPS", "25000.01")).isEqualTo(RiskResult.fail("PAY-LIMIT-001",
                "Requested 25000.01 GBP exceeds the single-payment limit 25000.00 GBP"));
        assertThat(risk("INTERNAL", "25000.01").reason()).contains("single-payment limit 25000.00 GBP");
    }

    @Test
    void chapsHasItsOwnLimitAndFee() {
        assertThat(risk("CHAPS", "50000.00")).isEqualTo(RiskResult.pass(true, "20.00"));
        assertThat(risk("CHAPS", "1000000.01")).isEqualTo(RiskResult.fail("PAY-LIMIT-001",
                "Requested 1000000.01 GBP exceeds the CHAPS limit 1000000.00 GBP"));
    }

    @Test
    void zeroOrNegativeIsRejectedFirst() {
        assertThat(risk("FPS", "0.00").code()).isEqualTo("GEN-VALID-001");
    }
}
