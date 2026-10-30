package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import org.springframework.data.jpa.domain.Specification;

public final class DomainRuleSpecification {

    private DomainRuleSpecification() {}

    public static Specification<DomainRule> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var pattern = LikePatterns.containing(search);
            return cb.or(
                    cb.like(cb.lower(root.get("domain")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("reason")), pattern, LikePatterns.ESCAPE));
        };
    }

    public static Specification<DomainRule> hasType(final DomainRuleType type) {
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

}
