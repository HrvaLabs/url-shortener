package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.service.dnsfilter.DnsFilterReport;
import cc.hrva.urlshortener.service.dnsfilter.DnsFilterStatus;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.List;

public record UrlCheckResponse(
        LinkVerdict.Outcome outcome,
        List<String> reasons,
        BlockReason blockReason,
        String blockDetail,
        String host,
        String registrableDomain,
        List<DnsFilterView> dnsFilters,
        boolean dnsFilterHostExists) {

    public record DnsFilterView(String filter, DnsFilterStatus status, String answer) {

    }

    public static UrlCheckResponse from(final LinkVerdict verdict, final String url) {
        return from(verdict, url, null);
    }

    public static UrlCheckResponse from(final LinkVerdict verdict, final String url, final DnsFilterReport report) {
        final var host = DomainNames.hostOf(url).orElse(null);
        final var dnsFilters = report == null ? List.<DnsFilterView>of() : report.answers().stream()
                .map(answer -> new DnsFilterView(answer.provider().label(), answer.status(), answer.answer()))
                .toList();
        return new UrlCheckResponse(verdict.outcome(), verdict.reasons(), verdict.blockReason(), verdict.blockDetail(), host,
                host == null ? null : DomainNames.registrableDomain(host), dnsFilters, report != null && report.exists());
    }

}
