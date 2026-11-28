package bank.pbp.common.market;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A scheme the customer asked for must be one the market offers them ({@link MarketPack#customerChoices()}): FPS or
 * CHAPS in the UK. Replaces the fixed {@code @Pattern(regexp = "FPS|CHAPS")}; a missing value means the default.
 */
@Documented
@Constraint(validatedBy = MarketSchemeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MarketScheme {

    String message() default "must be one of the market's payment schemes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
