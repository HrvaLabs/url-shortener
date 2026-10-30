package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.CustomDomain;
import io.swagger.v3.oas.annotations.media.Schema;

public record DomainRef(
        @Schema(description = "The custom domain's id", example = "7")
        long id,
        @Schema(description = "The custom domain's hostname", example = "go.example.com")
        String hostname) {

    public static DomainRef of(final CustomDomain domain) {
        return domain == null ? null : new DomainRef(domain.getId(), domain.getHostname());
    }

}
