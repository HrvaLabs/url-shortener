package cc.hrva.urlshortener.dto.domain;

import io.swagger.v3.oas.annotations.media.Schema;
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
public class DomainReviewDto {

    @Schema(description = "A note the owner sees, up to 500 characters after trimming; blank means none", example = "Looks fine")
    private String note;

    @Size(max = 500, message = "Keep it to 500 characters or fewer")
    public String getNote() {
        return StringUtils.stripToNull(note);
    }

}
