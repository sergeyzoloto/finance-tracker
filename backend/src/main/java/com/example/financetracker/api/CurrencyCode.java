package com.example.financetracker.api;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Currency;
import java.util.regex.Pattern;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * An ISO 4217 currency code, such as EUR (rule 1), checked as the ledger's validator checks a posting's currency.
 * Null is valid; add {@code @NotNull} where a currency is required.
 */
@Documented
@Constraint(validatedBy = CurrencyCode.Validator.class)
@Target({FIELD, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface CurrencyCode {

    String message() default "must be an ISO 4217 currency code, such as EUR";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<CurrencyCode, String> {

        private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (!CODE.matcher(value).matches()) {
                return false;
            }
            try {
                Currency.getInstance(value);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }
}
