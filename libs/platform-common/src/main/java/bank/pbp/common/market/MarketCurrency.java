package bank.pbp.common.market;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A currency field must be the deployment's market currency (the market pack's; GBP in the UK). Replaces the fixed
 * {@code @Pattern(regexp = "GBP")} (docs/design/market-packs.md, Stage 1 step 2).
 */
@Documented
@Constraint(validatedBy = MarketCurrencyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MarketCurrency {

    String message() default "must be the market currency";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
