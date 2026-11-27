package bank.pbp.bff.payments;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The payee account is valid in this market, in either request shape ({@link PayeeAccount}). */
@Documented
@Constraint(validatedBy = ValidAccountValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidAccount {

    String message() default "is not a valid account";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
