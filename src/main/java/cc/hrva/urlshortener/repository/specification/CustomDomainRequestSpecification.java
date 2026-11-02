package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.CustomDomainRequest;
import cc.hrva.urlshortener.model.enums.DomainRequestStatus;
import org.springframework.data.jpa.domain.Specification;

public final class CustomDomainRequestSpecification {

    private CustomDomainRequestSpecification() {}

    public static Specification<CustomDomainRequest> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var pattern = LikePatterns.containing(search);
            return cb.or(
                    cb.like(cb.lower(root.join("user").get("email")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("reason")), pattern, LikePatterns.ESCAPE));
        };
    }

    public static Specification<CustomDomainRequest> hasStatus(final DomainRequestStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

}
