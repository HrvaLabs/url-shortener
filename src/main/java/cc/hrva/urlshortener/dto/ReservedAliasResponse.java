package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.ReservedAlias;
import cc.hrva.urlshortener.model.Url;
import java.time.LocalDateTime;
import java.util.List;

public record ReservedAliasResponse(Long id, String alias, String reason, boolean builtIn, LocalDateTime createdAt, String createdBy,
                                    List<InUseLink> inUse) {

    public static ReservedAliasResponse from(final ReservedAlias reservedAlias, final List<InUseLink> inUse) {
        return new ReservedAliasResponse(reservedAlias.getId(), reservedAlias.getAlias(), reservedAlias.getReason(), false,
                reservedAlias.getCreatedAt(), reservedAlias.getCreatedBy(), inUse);
    }

    public record InUseLink(Long id, String shortUrl, String ownerEmail, boolean active) {

        public static InUseLink from(final Url url) {
            return new InUseLink(url.getId(), url.getShortUrl(), url.getOwner() != null ? url.getOwner().getEmail() : null, url.isActive());
        }

    }

}
