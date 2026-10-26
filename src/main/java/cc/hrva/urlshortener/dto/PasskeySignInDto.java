package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import tools.jackson.databind.JsonNode;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasskeySignInDto {

    @ToString.Exclude
    @NotBlank(message = "Ceremony ID is required")
    @Schema(description = "The ceremonyId from the sign-in options", example = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n")
    private String ceremonyId;

    @ToString.Exclude
    @NotNull(message = "Credential is required")
    @Schema(description = "The browser's PublicKeyCredential in its JSON form (toJSON())")
    private JsonNode credential;

    @Schema(description = "Whether to remember the user across sessions", example = "false")
    private Boolean rememberMe;

}
