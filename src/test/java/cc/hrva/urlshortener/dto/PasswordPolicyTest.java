package cc.hrva.urlshortener.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    private static final String TOO_SHORT = "Password must be at least 8 characters long";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private <T> java.util.List<String> violations(final T dto) {
        return validator.validate(dto).stream().map(violation -> violation.getMessage()).toList();
    }

    @Test
    void registrationNeedsAtLeastEightCharacters() {
        assertThat(violations(UserRegisterDto.builder().email("a@b.co").password("short").build())).contains(TOO_SHORT);
        assertThat(violations(UserRegisterDto.builder().email("a@b.co").password("longenough").build())).doesNotContain(TOO_SHORT);
    }

    @Test
    void passwordResetNeedsAtLeastEightCharacters() {
        assertThat(violations(PasswordResetDto.builder().token("t").email("a@b.co").password("1").build())).contains(TOO_SHORT);
    }

    @Test
    void passwordChangeNeedsAtLeastEightCharacters() {
        assertThat(violations(UpdatePasswordDto.builder().oldPassword("whatever").newPassword("1234567").build())).contains(TOO_SHORT);
    }

}
