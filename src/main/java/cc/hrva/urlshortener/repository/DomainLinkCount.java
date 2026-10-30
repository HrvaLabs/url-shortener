package cc.hrva.urlshortener.repository;

public record DomainLinkCount(Long domainId, Long links, Long clicks) {
}
