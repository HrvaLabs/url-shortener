package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.codebook.Authorities;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class UserDto {

    @Schema(description = "Unique identifier", example = "1")
    private Long id;
    @Schema(description = "Email address", example = "user@example.com")
    private String email;
    @Schema(description = "Number of API key slots available", example = "4")
    private Long apiKeySlots;
    @Schema(description = "User authorities/roles", example = "[\"ROLE_USER\"]")
    private List<Authorities> authorities;
    @Schema(description = "Account creation date and time", example = "2026-05-16T10:00:00")
    private LocalDateTime createDate;
    @Schema(description = "Last login date and time", example = "2026-05-16T12:00:00")
    private LocalDateTime lastLogin;
    @Schema(description = "Authentication provider", example = "local", allowableValues = {"local", "google", "github"})
    private String authProvider;
    @Schema(description = "Whether two-factor authentication is enabled", example = "false")
    private Boolean twoFactorEnabled;
    @Schema(description = "Whether two-step verification is on", example = "false")
    private Boolean twoStepEnabled;
    @Schema(description = "Every way this account has signed in, email and password first, then in the order first used")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SignInMethodResponse> signInMethods;
    @Schema(description = "Whether the account has a password it can sign in with; only on GET /users/me", example = "true")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean passwordSet;

}
