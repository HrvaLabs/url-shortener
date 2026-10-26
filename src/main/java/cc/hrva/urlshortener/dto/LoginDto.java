package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class LoginDto {

    @NotBlank(message = "Email is required")
    @Schema(description = "User email address", example = "user@example.com")
    private String email;

    @NotBlank(message = "Password is required")
    @Schema(description = "User password", example = "securePassword123")
    private String password;

    @Schema(description = "Whether to remember the user across sessions", example = "false")
    private Boolean rememberMe;

    @Deprecated(forRemoval = true)
    @Schema(deprecated = true, description = "Deprecated: finish two-step sign-ins with POST /api/v1/auth/2fa/totp instead. TOTP or recovery code, sent again with the password", example = "123456")
    private String code;

}
