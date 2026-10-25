package com.ergo.connect.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** Like {@code @NotBlank}, but invisible characters (no-break space, zero-width space, ...) count as blank. */
@Documented
@Constraint(validatedBy = NotInvisiblyBlank.Validator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface NotInvisiblyBlank {

	String message() default "must not be blank";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<NotInvisiblyBlank, CharSequence> {

		@Override
		public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
			return !Blank.is(value == null ? null : value.toString());
		}

	}

}
