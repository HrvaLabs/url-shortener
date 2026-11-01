package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ApiKeyCreateDto(
        @Min(value = 1, message = ApiKeyCreateDto.DAYS_RANGE)
        @Max(value = 3650, message = ApiKeyCreateDto.DAYS_RANGE)
        @Schema(description = "Days until the key expires, 1 to 3650. Leave it and neverExpires out for the default expiry", example = "90")
        Integer expiresInDays,
        @Schema(description = "true makes a key that never expires; it works until it is revoked", example = "false")
        Boolean neverExpires) {

    public static final String DAYS_RANGE = "Use a number of days from 1 to 3,650.";

}
