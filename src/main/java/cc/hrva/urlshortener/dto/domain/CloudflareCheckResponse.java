package cc.hrva.urlshortener.dto.domain;

import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck.TokenState;
import java.time.LocalDateTime;

public record CloudflareCheckResponse(CloudflareTokenStatus status, LocalDateTime checkedAt, String message) {

    public static CloudflareCheckResponse of(final TokenState state) {
        return new CloudflareCheckResponse(state.status(), state.checkedAt(), state.message());
    }

}
