package bank.pbp.bff;

import static org.assertj.core.api.Assertions.assertThat;

import bank.pbp.market.uk.UkMarketPack;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** GET /v1/market describes the UK exactly as the screens assumed it before (GBP, en-GB, London, sort codes). */
class MarketControllerTest {

    private final Map<String, Object> uk = new MarketController(new UkMarketPack(new BigDecimal("25000.00"),
            new BigDecimal("10000.00"), new BigDecimal("1000000.00"), new BigDecimal("20.00"), LocalTime.of(6, 0),
            LocalTime.of(17, 30), Set.of(), new BigDecimal("5000.00"))).market();

    @Test
    void currencyLanguageAndTime() {
        assertThat(uk).containsEntry("code", "uk").containsEntry("currency", "GBP")
                .containsEntry("locale", "en-GB").containsEntry("zone", "Europe/London");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemesWithChapsHoursAndFee() {
        assertThat(MarketController.schemeCodes(uk)).containsExactly("INTERNAL", "FPS", "CHAPS");
        Map<String, Object> chaps = ((List<Map<String, Object>>) uk.get("paymentSchemes")).get(2);
        assertThat(chaps).containsEntry("opens", "06:00").containsEntry("cutOff", "17:30")
                .containsEntry("fee", "20.00").containsEntry("limit", "1000000.00").containsEntry("alwaysOpen", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitsAccountFieldsNameCheckAndDirectDebits() {
        assertThat((Map<String, Object>) uk.get("limits")).containsEntry("reviewThreshold", "10000.00");
        Map<String, Object> id = (Map<String, Object>) uk.get("accountIdentifier");
        assertThat(id).containsEntry("scheme", "UK_SORT_CODE");
        assertThat((List<Map<String, Object>>) id.get("fields")).extracting(f -> f.get("label"))
                .containsExactly("Sort code", "Account number");
        assertThat((Map<String, Object>) uk.get("nameCheck")).containsEntry("name", "Confirmation of Payee");
        assertThat((Map<String, Object>) uk.get("directDebits")).containsEntry("name", "Bacs Direct Debit");
    }
}
