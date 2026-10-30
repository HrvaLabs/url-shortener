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
public class DomainDisableDto {

    @Schema(description = "Why the domain is turned off, up to 500 characters after trimming; the owner sees it", example = "Phishing")
    private String note;

    @NotBlank(message = "Reason is required")
    @Size(max = 500, message = "Keep it to 500 characters or fewer")
    public String getNote() {
        return StringUtils.strip(note);
    }

}
