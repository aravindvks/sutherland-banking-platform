package bank.pbp.common.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The scheme a customer asks for: in the UK exactly the old @Pattern("FPS|CHAPS"); elsewhere the market's choices. */
class MarketSchemeValidatorTest {

    private static PaymentScheme scheme(String code) {
        return new PaymentScheme(code, code, true, OperatingWindow.ALWAYS, Optional.empty(), BigDecimal.ZERO);
    }

    private static MarketSchemeValidator validator(List<PaymentScheme> choices) {
        MarketPack market = mock(MarketPack.class);
        when(market.customerChoices()).thenReturn(choices);
        return new MarketSchemeValidator(market);
    }

    @Test
    void ukTakesFpsAndChapsOnly() {
        MarketSchemeValidator uk = validator(List.of(scheme("FPS"), new PaymentScheme("CHAPS", "CHAPS", false,
                OperatingWindow.businessHours(LocalTime.of(6, 0), LocalTime.of(17, 30)), Optional.empty(),
                new BigDecimal("20.00"))));
        assertThat(uk.isValid(null, null)).isTrue();
        assertThat(uk.isValid("FPS", null)).isTrue();
        assertThat(uk.isValid("CHAPS", null)).isTrue();
        ConstraintValidatorContext context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
        assertThat(uk.isValid("BACS", context)).isFalse();
        verify(context).buildConstraintViolationWithTemplate("must be FPS or CHAPS");
        assertThat(uk.isValid("INTERNAL", mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS))).isFalse();
    }

    @Test
    void anotherMarketTakesItsOwnChoices() {
        MarketSchemeValidator us = validator(List.of(scheme("RTP"), scheme("ACH"), scheme("WIRE")));
        assertThat(us.isValid("ACH", null)).isTrue();
        ConstraintValidatorContext context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
        assertThat(us.isValid("CHAPS", context)).isFalse();
        verify(context).buildConstraintViolationWithTemplate("must be RTP, ACH or WIRE");
    }
}
