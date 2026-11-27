package bank.pbp.common.market;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MarketGuardTest {

    private static MarketPack pack(String code) {
        MarketPack p = mock(MarketPack.class);
        when(p.code()).thenReturn(code);
        when(p.currency()).thenReturn(Currency.getInstance("GBP"));
        when(p.zone()).thenReturn(ZoneId.of("Europe/London"));
        when(p.locale()).thenReturn(Locale.UK);
        return p;
    }

    @Test
    void matchingMarketStarts() {
        assertThatCode(() -> MarketGuard.check("uk", pack("uk"))).doesNotThrowAnyException();
        assertThatCode(() -> MarketGuard.check(" UK ", pack("uk"))).doesNotThrowAnyException();
    }

    @Test
    void unsetMarketMeansUk() {
        assertThatCode(() -> MarketGuard.check(null, pack("uk"))).doesNotThrowAnyException();
        assertThatCode(() -> MarketGuard.check("", pack("uk"))).doesNotThrowAnyException();
    }

    @Test
    void wrongPackRefusesToStart() {
        assertThatThrownBy(() -> MarketGuard.check("us", pack("uk")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'uk' market pack but PBP_MARKET is 'us'");
    }

    @Test
    void serviceWithoutAPackIsNotChecked() {
        assertThatCode(() -> MarketGuard.check("us", null)).doesNotThrowAnyException();
    }
}
