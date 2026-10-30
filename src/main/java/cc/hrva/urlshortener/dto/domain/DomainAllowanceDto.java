package cc.hrva.urlshortener.dto.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DomainAllowanceDto {

    private static final String RANGE = "Choose 1 to 20";

    @Schema(description = "How many custom domains the account may have, 1 to 20", example = "3")
    @NotNull(message = RANGE)
    @Min(value = 1, message = RANGE)
    @Max(value = 20, message = RANGE)
    private Integer maxDomains;

    @Schema(description = "Why the account gets this limit, 1 to 300 characters after trimming", example = "Agency with three brands")
    private String reason;

    @NotBlank(message = "Reason is required")
    @Size(max = 300, message = "Keep it to 300 characters or fewer")
    public String getReason() {
        return StringUtils.strip(reason);
    }

}
