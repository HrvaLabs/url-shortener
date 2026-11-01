package cc.hrva.urlshortener.validator.constraint;

import cc.hrva.urlshortener.security.BcryptPasswords;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordFitsBcryptValidator implements ConstraintValidator<PasswordFitsBcrypt, CharSequence> {

    public static final String TOO_LONG = "This password is too long. Use at most 72 bytes; accented letters count as two.";

    @Override
    public boolean isValid(final CharSequence password, final ConstraintValidatorContext context) {
        return password == null || !BcryptPasswords.tooLong(password);
    }

}
