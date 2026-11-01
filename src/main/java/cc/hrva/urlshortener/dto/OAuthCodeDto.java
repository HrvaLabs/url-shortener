package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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

}
