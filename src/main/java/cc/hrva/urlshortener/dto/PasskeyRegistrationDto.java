package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
public class PasskeyRegistrationDto {

    @ToString.Exclude
    @NotBlank(message = "Ceremony ID is required")
    @Schema(description = "The ceremonyId from the registration options", example = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n")
    private String ceremonyId;

    @ToString.Exclude
    @NotNull(message = "Credential is required")
    @Schema(description = "The browser's PublicKeyCredential in its JSON form (toJSON())")
    private JsonNode credential;

    @Size(max = 64, message = "A label can have at most 64 characters.")
    @Schema(description = "A name for the passkey. Blank becomes Passkey", example = "MacBook")
    private String label;

}
