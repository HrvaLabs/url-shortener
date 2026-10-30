package cc.hrva.urlshortener.dto.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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
public class CustomDomainApplyDto {

    @Schema(description = "A subdomain you own; case, a trailing dot and Unicode are normalized (Unicode becomes punycode)", example = "go.example.com")
    @NotBlank(message = "Enter a domain, like go.example.com")
    private String hostname;

    @Schema(description = "What the domain is for, 1 to 300 characters after trimming", example = "Menu links for my cafe")
    private String reason;

    @NotBlank(message = "Tell us what you'll use it for")
    @Size(max = 300, message = "Keep it to 300 characters or fewer")
    public String getReason() {
        return StringUtils.strip(reason);
    }

}
