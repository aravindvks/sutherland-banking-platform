package bank.pbp.common.market;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Checks {@link MarketCurrency}. Spring builds the validator with the market pack bean. In the UK the message is
 * unchanged: "must be GBP in the UK cell".
 */
public class MarketCurrencyValidator implements ConstraintValidator<MarketCurrency, String> {

    private final String currency;
    private final String message;

    public MarketCurrencyValidator(MarketPack market) {
        this.currency = market.currency().getCurrencyCode();
        this.message = "must be " + currency + " in the " + market.code().toUpperCase() + " cell";
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || currency.equals(value)) {
            return true;   // a missing value is @NotBlank's to report
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
