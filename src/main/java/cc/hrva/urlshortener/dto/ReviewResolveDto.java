package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.ReviewAction;
import jakarta.validation.constraints.NotNull;

public record ReviewResolveDto(@NotNull(message = ReviewResolveDto.ACTION_REQUIRED) ReviewAction action, String domain) {

    public static final String ACTION_REQUIRED = "Action must be OK, TRUST_DOMAIN, BLOCK_LINK, BLOCK_DOMAIN or BLOCK_USER";

}
