package cc.hrva.urlshortener.dto.bio;

import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioTheme;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BioPageUpdateDto {

    public static final int MAX_DISPLAY_NAME_LENGTH = 50;
    public static final int MAX_BIO_LENGTH = 160;
    public static final String DISPLAY_NAME_TOO_LONG = "Name must be 50 characters or fewer";
    public static final String BIO_TOO_LONG = "Bio must be 160 characters or fewer";

    @Size(max = MAX_DISPLAY_NAME_LENGTH, message = DISPLAY_NAME_TOO_LONG)
    @Schema(description = "Display name, at most 50 characters", example = "Ana Horvat")
    private String displayName;
    @Size(max = MAX_BIO_LENGTH, message = BIO_TOO_LONG)
    @Schema(description = "Short bio, at most 160 characters; shown as plain text", example = "Photos from Zagreb")
    private String bio;
    @NotNull(message = "Choose an avatar")
    @Schema(description = "INITIALS (computed from the name or handle) or EMOJI", example = "EMOJI")
    private BioAvatarKind avatarKind;
    @Schema(description = "One emoji when avatarKind is EMOJI; ignored and cleared for INITIALS")
    private String avatarEmoji;
    @NotNull(message = "Choose an accent colour")
    @Schema(description = "Accent colour", example = "TEAL")
    private BioAccent accent;
    @NotNull(message = "Choose a theme")
    @Schema(description = "AUTO follows the visitor's system setting", example = "AUTO")
    private BioTheme theme;
    @Schema(description = "Whether search engines may index the page; false when left out", example = "false")
    private Boolean indexable;
    @Valid
    @NotNull
    @Size(max = 30, message = "A page can have at most 30 buttons")
    @Schema(description = "Every button in page order; the page is replaced as a whole")
    private List<@NotNull(message = BioPageButtonDto.LINK_REQUIRED) BioPageButtonDto> buttons;

}
