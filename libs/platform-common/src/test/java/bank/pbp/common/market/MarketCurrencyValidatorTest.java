package bank.pbp.common.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.validation.ConstraintValidatorContext;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/** The payment API's currency check follows the market pack; in the UK it behaves exactly as @Pattern("GBP") did. */
class MarketCurrencyValidatorTest {

    private static MarketCurrencyValidator validator(String code, String currency) {
        MarketPack market = mock(MarketPack.class);
        when(market.code()).thenReturn(code);
        when(market.currency()).thenReturn(Currency.getInstance(currency));
        return new MarketCurrencyValidator(market);
    }

    @Test
    void ukAcceptsGbpOnly() {
        MarketCurrencyValidator uk = validator("uk", "GBP");
        assertThat(uk.isValid("GBP", null)).isTrue();
        assertThat(uk.isValid(null, null)).isTrue();   // @NotBlank reports a missing currency

        ConstraintValidatorContext context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
        assertThat(uk.isValid("EUR", context)).isFalse();
        assertThat(uk.isValid("gbp", mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS))).isFalse();
        verify(context).buildConstraintViolationWithTemplate("must be GBP in the UK cell");
    }

    @Test
    void anotherMarketUsesItsOwnCurrency() {
        MarketCurrencyValidator us = validator("us", "USD");
        assertThat(us.isValid("USD", null)).isTrue();
        ConstraintValidatorContext context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
        assertThat(us.isValid("GBP", context)).isFalse();
        verify(context).buildConstraintViolationWithTemplate("must be USD in the US cell");
    }
}
