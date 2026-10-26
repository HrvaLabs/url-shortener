package cc.hrva.urlshortener.dto.bio;

import cc.hrva.urlshortener.model.enums.BioIcon;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BioPageButtonDto {

    public static final int MAX_TITLE_LENGTH = 60;
    public static final String TITLE_REQUIRED = "Give the button a title";
    public static final String TITLE_TOO_LONG = "Button title must be 60 characters or fewer";
    public static final String LINK_REQUIRED = "Pick a link";

    @NotNull(message = LINK_REQUIRED)
    @Schema(description = "Id of one of your own short links", example = "41")
    private Long urlId;
    @NotBlank(message = TITLE_REQUIRED)
    @Size(max = MAX_TITLE_LENGTH, message = TITLE_TOO_LONG)
    @Schema(description = "Button text, at most 60 characters", example = "Instagram")
    private String title;
    @Schema(description = "Optional icon; null shows none", example = "INSTAGRAM")
    private BioIcon icon;
    @Schema(description = "Whether the button shows on the page; true when left out", example = "true")
    private Boolean visible;

}
