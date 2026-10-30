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
public class RemovePasswordDto {

    @ToString.Exclude
    @NotBlank(message = "Enter your current password.")
    @Schema(description = "The account's current password", example = "user123")
    private String currentPassword;

}
