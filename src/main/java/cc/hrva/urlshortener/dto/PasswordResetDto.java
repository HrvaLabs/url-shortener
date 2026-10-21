package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class PasswordResetDto {

    @NotBlank(message = "Token is required")
    @Schema(description = "Password reset token", example = "reset-token-abc123")
    private String token;

    @NotBlank(message = "Email is required")
    @Schema(description = "User email address", example = "user@example.com")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters long")
    @Schema(description = "New password", example = "newSecurePassword456")
    private String password;
    
}
