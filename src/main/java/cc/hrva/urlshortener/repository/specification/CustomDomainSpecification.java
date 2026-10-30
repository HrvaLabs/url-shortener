package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

public final class CustomDomainSpecification {

    private CustomDomainSpecification() {}

    public static Specification<CustomDomain> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var pattern = LikePatterns.containing(search);
            final var owner = root.join("owner", JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(root.get("hostname")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(owner.get("email")), pattern, LikePatterns.ESCAPE));
        };
    }

    public static Specification<CustomDomain> hasStatus(final CustomDomainStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

}
