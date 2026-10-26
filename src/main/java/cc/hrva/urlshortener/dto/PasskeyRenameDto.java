package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasskeyRenameDto {

    @NotBlank(message = "Label is required")
    @Size(max = 64, message = "A label can have at most 64 characters.")
    @Schema(description = "The new name", example = "Work YubiKey")
    private String label;

}
