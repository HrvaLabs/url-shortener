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
public class PendingTokenDto {

    @ToString.Exclude
    @NotBlank(message = "Pending token is required")
    @Schema(description = "The pendingToken from the login response", example = "Pn4m1qL0rT8vWx2yZ3aB4cD5eF6gH7iJ8kL9mN0oP1q")
    private String pendingToken;

}
