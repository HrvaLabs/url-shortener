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
public class DomainRequestDto {

    private static final String RANGE = "Choose 2 to " + DomainAllowanceDto.MAX_DOMAINS;

    @Schema(description = "How many custom domains you want in total, 2 to 20 and more than your limit", example = "3")
    @NotNull(message = RANGE)
    @Min(value = 2, message = RANGE)
    @Max(value = DomainAllowanceDto.MAX_DOMAINS, message = RANGE)
    private Integer requestedTotal;

    @Schema(description = "What you'll use the domains for, 1 to 300 characters after trimming", example = "One domain for each of my three shops")
    private String reason;

    @NotBlank(message = "Reason is required")
    @Size(max = 300, message = "Keep it to 300 characters or fewer")
    public String getReason() {
        return StringUtils.strip(reason);
    }

}
