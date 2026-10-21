package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.List;

public record UrlCheckResponse(
        LinkVerdict.Outcome outcome,
        List<String> reasons,
        BlockReason blockReason,
        String blockDetail,
        String host,
        String registrableDomain) {

    public static UrlCheckResponse from(final LinkVerdict verdict, final String url) {
        final var host = DomainNames.hostOf(url).orElse(null);
        return new UrlCheckResponse(verdict.outcome(), verdict.reasons(), verdict.blockReason(), verdict.blockDetail(), host,
                host == null ? null : DomainNames.registrableDomain(host));
    }

}
