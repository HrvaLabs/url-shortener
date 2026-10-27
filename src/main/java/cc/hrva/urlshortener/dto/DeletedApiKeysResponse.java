package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record DeletedApiKeysResponse(
        @Schema(description = "How many of your inactive API keys were deleted", example = "3")
        int deleted) {
}
