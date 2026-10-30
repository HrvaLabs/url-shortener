package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.UserSignInMethod;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

public record SignInMethodResponse(
        @Schema(description = "How the account signed in", example = "GOOGLE") SignInMethod method,
        @Schema(description = "First sign-in with this method", example = "2026-05-16T10:00:00") LocalDateTime firstUsedAt,
        @Schema(description = "Latest sign-in with this method", example = "2026-10-29T08:00:00") LocalDateTime lastUsedAt) {

    public static SignInMethodResponse from(final UserSignInMethod signInMethod) {
        return new SignInMethodResponse(signInMethod.getMethod(), signInMethod.getFirstUsedAt(), signInMethod.getLastUsedAt());
    }

}
