package cc.hrva.urlshortener.dto.bio;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BioHandleDto {

    @Schema(description = "The new handle: 3 to 30 letters, numbers, hyphens and underscores; a leading @ and capitals are ignored", example = "ana-horvat")
    private String handle;

}
