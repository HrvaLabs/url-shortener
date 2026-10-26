package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasskeyRegistrationOptionsDto {

    @ToString.Exclude
    @Schema(description = "Current password, required for local (email/password) accounts", example = "user123")
    private String password;

}
