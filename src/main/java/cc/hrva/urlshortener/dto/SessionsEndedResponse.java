package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record SessionsEndedResponse(
        @Schema(description = "A new sign-in token for this device, with the same expiry as the one it replaces; every other device is signed out",
                example = "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJ1c2VyQGV4YW1wbGUuY29tIn0.c2lnbmF0dXJl") String token) {
}
