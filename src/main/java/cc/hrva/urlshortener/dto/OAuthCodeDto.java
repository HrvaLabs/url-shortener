package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthCodeDto {

    @ToString.Exclude
    @NotBlank(message = "Code is required")
    @Schema(description = "The one-time code from the sign-in callback's ?code=, valid for 60 seconds and for one exchange",
            example = "Pn4m1qL0rT8vWx2yZ3aB4cD5eF6gH7iJ8kL9mN0oP1q")
    private String code;

    @ToString.Exclude
    @NotBlank(message = "Verifier is required")
    @Size(max = 128, message = "Verifier must be 128 characters or fewer")
    @Schema(description = "The PKCE-style verifier (RFC 7636: 43 to 128 characters) whose base64url SHA-256 was sent as challenge when the sign-in started",
            example = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
    private String verifier;

}
