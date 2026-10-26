package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
public class TwoStepDisableDto {

    @ToString.Exclude
    @Schema(description = "A TOTP code from the authenticator app, or a recovery code. Send it alone, or send ceremonyId and credential instead", example = "123456")
    private String code;

    @ToString.Exclude
    @Schema(description = "The ceremonyId from the turn-off options", example = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n")
    private String ceremonyId;

    @ToString.Exclude
    @Schema(description = "The browser's PublicKeyCredential in its JSON form (toJSON())")
    private JsonNode credential;

}
