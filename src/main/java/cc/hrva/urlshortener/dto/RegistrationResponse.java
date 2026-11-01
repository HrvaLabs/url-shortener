package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The answer to a sign-up. It is the same whether or not an account already uses the address.")
public record RegistrationResponse(
        @Schema(description = "The address as sent", example = "user@example.com") String email,
        @Schema(description = "What to do next", example = "Check your inbox for an email from hrva.cc.") String message) {

    public static final String CHECK_YOUR_INBOX = "Check your inbox for an email from hrva.cc.";

    public static RegistrationResponse of(final String email) {
        return new RegistrationResponse(email, CHECK_YOUR_INBOX);
    }

}
