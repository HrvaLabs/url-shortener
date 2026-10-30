package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.ApiKey;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

public final class ApiKeySpecification {

    private ApiKeySpecification() {}

    public static Specification<ApiKey> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var owner = root.join("owner", JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(owner.get("email")), LikePatterns.containing(search), LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("key")), LikePatterns.startingWith(search), LikePatterns.ESCAPE));
        };
    }

    public static Specification<ApiKey> hasActive(final boolean active) {
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }

}
