package bank.pbp.bff.payments;

import bank.pbp.common.market.AccountIdentifierScheme;
import bank.pbp.common.market.MarketPack;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Map;

/**
 * Checks {@link ValidAccount} with the market pack. For the original shape the errors stay where they were
 * ({@code creditor.sortCode must be 6 digits}); for the new shape they are on {@code account.<field>}.
 */
public class ValidAccountValidator implements ConstraintValidator<ValidAccount, PayeeAccount> {

    private final AccountIdentifierScheme scheme;

    public ValidAccountValidator(MarketPack market) {
        this.scheme = market.accountIdentifiers();
    }

    @Override
    public boolean isValid(PayeeAccount value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = true;
        if (value.legacyShape()) {
            if (!scheme.scheme().equals(PayeeAccount.LEGACY_SCHEME)) {
                return report(context, "account", "is required in this market");
            }
            for (String field : new String[] {"sortCode", "accountNumber"}) {
                String v = "sortCode".equals(field) ? value.sortCode() : value.accountNumber();
                if (v == null || v.isBlank()) {
                    valid = report(context, field, "must not be blank");
                }
            }
            if (!valid) {
                return false;
            }
            for (Map.Entry<String, String> p : scheme.problems(value.identifier()).entrySet()) {
                valid = report(context, p.getKey(), p.getValue());
            }
            return valid;
        }
        if (value.account().scheme() == null || value.account().fields() == null) {
            return true;   // @NotBlank and @NotNull on AccountBody report these
        }
        if (value.sortCode() != null || value.accountNumber() != null) {
            return report(context, "account", "give either account or sortCode and accountNumber, not both");
        }
        for (Map.Entry<String, String> p : scheme.problems(value.identifier()).entrySet()) {
            context.buildConstraintViolationWithTemplate(p.getValue())
                    .addPropertyNode("account").addPropertyNode(p.getKey()).addConstraintViolation();
            valid = false;
        }
        return valid;
    }

    private static boolean report(ConstraintValidatorContext context, String property, String message) {
        context.buildConstraintViolationWithTemplate(message).addPropertyNode(property).addConstraintViolation();
        return false;
    }
}
