package bank.pbp.common.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ActiveMarketTest {

    @AfterEach
    void backToTheUk() {
        ActiveMarket.reset();
    }

    @Test
    void untilAPackIsSetItAnswersAsTheUk() {
        assertThat(ActiveMarket.zone()).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(ActiveMarket.hasNameCheck()).isTrue();
        assertThat(ActiveMarket.hasHours("CHAPS")).isTrue();
        assertThat(ActiveMarket.hasHours("FPS")).isFalse();
        assertThat(ActiveMarket.isInstant("FPS")).isTrue();
        assertThat(ActiveMarket.isInstant("CHAPS")).isFalse();
        assertThat(ActiveMarket.schemeName("CHAPS")).isEqualTo("CHAPS");
        assertThat(ActiveMarket.currencySymbol()).isEqualTo("£");
    }

    @Test
    void takesItsFactsFromThePack() {
        MarketPack pack = mock(MarketPack.class);
        when(pack.code()).thenReturn("zz");
        when(pack.zone()).thenReturn(ZoneId.of("America/New_York"));
        when(pack.locale()).thenReturn(Locale.US);
        when(pack.currency()).thenReturn(Currency.getInstance("USD"));
        when(pack.nameCheck()).thenReturn(Optional.empty());
        when(pack.paymentSchemes()).thenReturn(List.of(
                new PaymentScheme("INSTANT", "Instant", true, OperatingWindow.ALWAYS, Optional.empty(), BigDecimal.ZERO),
                new PaymentScheme("WIRE", "Wire transfer", false,
                        OperatingWindow.businessHours(LocalTime.of(9, 0), LocalTime.of(17, 0)), Optional.empty(),
                        new BigDecimal("25.00")),
                new PaymentScheme("ACH", "ACH transfer", false, OperatingWindow.ALWAYS, Optional.empty(),
                        BigDecimal.ZERO)));
        MarketGuard.check("zz", pack);
        assertThat(ActiveMarket.hasNameCheck()).isFalse();
        assertThat(ActiveMarket.hasHours("WIRE")).isTrue();
        assertThat(ActiveMarket.hasHours("ACH")).isFalse();
        assertThat(ActiveMarket.hasHours("CHAPS")).isFalse();
        assertThat(ActiveMarket.isInstant("ACH")).isFalse();
        assertThat(ActiveMarket.schemeName("WIRE")).isEqualTo("Wire transfer");
        assertThat(ActiveMarket.currencySymbol()).isEqualTo("$");
    }
}
