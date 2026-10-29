package cc.hrva.urlshortener.dto.notification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationConsentDto {

    @NotNull(message = "Choose on or off")
    @Schema(description = "true turns the email on, false turns it off", example = "true")
    private Boolean consented;

}
