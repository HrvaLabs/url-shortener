package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Data
@Builder
@AllArgsConstructor
public class JWTTokenDto {

    @Schema(description = "JWT access token", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyQGV4YW1wbGUuY29tIn0.xYs3AeX0e0mZEp7Q0wUq1A")
    private String token;
    @Schema(description = "Authenticated user details")
    private UserDto user;
    @Schema(description = "True when the password was right and a second step is required. Kept for older clients; read twoStepRequired instead", example = "false")
    private Boolean twoFactorRequired;
    @Schema(description = "True when the password was right and a second step is required to finish signing in", example = "true")
    private Boolean twoStepRequired;
    @ToString.Exclude
    @Schema(description = "Short-lived token for the second step, valid for 5 minutes", example = "Pn4m1qL0rT8vWx2yZ3aB4cD5eF6gH7iJ8kL9mN0oP1q")
    private String pendingToken;
    @Schema(description = "Second steps the account can use", example = "[\"TOTP\", \"RECOVERY_CODE\"]")
    private List<TwoStepMethod> methods;

    public static JWTTokenDto twoStepRequired(final String pendingToken, final List<TwoStepMethod> methods) {
        return JWTTokenDto.builder()
                .twoFactorRequired(true)
                .twoStepRequired(true)
                .pendingToken(pendingToken)
                .methods(methods)
                .build();
    }

}
