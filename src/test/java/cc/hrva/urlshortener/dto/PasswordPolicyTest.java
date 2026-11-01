package cc.hrva.urlshortener.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    private static final String TOO_SHORT = "Password must be at least 8 characters long";
    private static final String TOO_LONG = "This password is too long. Use at most 72 bytes; accented letters count as two.";
    private static final String SEVENTY_TWO_BYTES = "a".repeat(72);
    private static final String SEVENTY_THREE_BYTES = "a".repeat(73);
    private static final String THIRTY_SIX_ACCENTED = "\u010d".repeat(36);
    private static final String THIRTY_SEVEN_ACCENTED = "\u010d".repeat(37);

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


    @Test
    void everyPasswordThatIsSetFitsInSeventyTwoBytes() {
        assertThat(violationsOf(UserRegisterDto.builder().email("a@b.co").password(SEVENTY_THREE_BYTES).build())).containsEntry("password", TOO_LONG);
        assertThat(violationsOf(PasswordResetDto.builder().token("t").email("a@b.co").password(SEVENTY_THREE_BYTES).build())).containsEntry("password", TOO_LONG);
        assertThat(violationsOf(UpdatePasswordDto.builder().oldPassword("whatever").newPassword(SEVENTY_THREE_BYTES).build())).containsEntry("newPassword", TOO_LONG);
        assertThat(violationsOf(CreateUrlDto.builder().longUrl("https://example.com").password(SEVENTY_THREE_BYTES).build())).containsEntry("password", TOO_LONG);
        assertThat(violationsOf(UrlUpdateDto.builder().id(1L).password(SEVENTY_THREE_BYTES).build())).containsEntry("password", TOO_LONG);
    }

    @Test
    void seventyTwoBytesIsTheMostAndAccentedLettersCountAsTwo() {
        assertThat(violations(UserRegisterDto.builder().email("a@b.co").password(SEVENTY_TWO_BYTES).build())).isEmpty();
        assertThat(violations(UserRegisterDto.builder().email("a@b.co").password(THIRTY_SIX_ACCENTED).build())).isEmpty();
        assertThat(violations(UserRegisterDto.builder().email("a@b.co").password(THIRTY_SEVEN_ACCENTED).build())).containsExactly(TOO_LONG);
        assertThat(violations(UpdatePasswordDto.builder().oldPassword(SEVENTY_THREE_BYTES).newPassword(SEVENTY_TWO_BYTES).build()))
                .as("the current password is checked, not set").isEmpty();
    }

    @Test
    void anEmptyLinkPasswordStillClearsIt() {
        assertThat(violations(UrlUpdateDto.builder().id(1L).password("").build())).isEmpty();
        assertThat(violations(CreateUrlDto.builder().longUrl("https://example.com").build())).isEmpty();
    }

    private <T> java.util.Map<String, String> violationsOf(final T dto) {
        return validator.validate(dto).stream().collect(java.util.stream.Collectors.toMap(
                violation -> violation.getPropertyPath().toString(), violation -> violation.getMessage(), (a, b) -> b));
    }

}
