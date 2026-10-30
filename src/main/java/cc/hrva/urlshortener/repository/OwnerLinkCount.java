package cc.hrva.urlshortener.repository;

public record OwnerLinkCount(Long ownerId, Long links, Long blockedLinks) {
}
