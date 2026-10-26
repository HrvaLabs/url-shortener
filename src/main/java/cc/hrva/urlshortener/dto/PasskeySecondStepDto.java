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
public class PasskeySecondStepDto {

    @ToString.Exclude
    @NotBlank(message = "Pending token is required")
    @Schema(description = "The pendingToken from the login response", example = "Pn4m1qL0rT8vWx2yZ3aB4cD5eF6gH7iJ8kL9mN0oP1q")
    private String pendingToken;

    @ToString.Exclude
    @NotBlank(message = "Ceremony ID is required")
    @Schema(description = "The ceremonyId from the second-step options", example = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n")
    private String ceremonyId;

    @ToString.Exclude
    @NotNull(message = "Credential is required")
    @Schema(description = "The browser's PublicKeyCredential in its JSON form (toJSON())")
    private JsonNode credential;

}
