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
public class SignInMethodTurnOnDto {

    @ToString.Exclude
    @Schema(description = "Current password, required while the account has one; without one, a sign-in within the last 15 minutes", example = "user123")
    private String password;

}
