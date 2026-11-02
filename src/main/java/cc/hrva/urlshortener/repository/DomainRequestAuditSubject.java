package cc.hrva.urlshortener.repository;

public record DomainRequestAuditSubject(String email, int limitAtRequest, int requestedTotal) {
}
