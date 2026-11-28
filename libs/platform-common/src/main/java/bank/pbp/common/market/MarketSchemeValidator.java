package bank.pbp.common.market;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;

/**
 * Checks {@link MarketScheme}. Spring builds the validator with the market pack bean. In the UK the accepted values are
 * exactly the old pattern's, FPS and CHAPS; the message names them: "must be FPS or CHAPS".
 */
public class MarketSchemeValidator implements ConstraintValidator<MarketScheme, String> {

    private final List<String> codes;
    private final String message;

    public MarketSchemeValidator(MarketPack market) {
        this.codes = market.customerChoices().stream().map(PaymentScheme::code).toList();
        this.message = "must be " + (codes.size() == 1 ? codes.get(0)
                : String.join(", ", codes.subList(0, codes.size() - 1)) + " or " + codes.get(codes.size() - 1));
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || codes.contains(value)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
