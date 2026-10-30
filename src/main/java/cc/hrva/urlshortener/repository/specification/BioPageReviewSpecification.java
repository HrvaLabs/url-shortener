package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.BioPageReview;
import org.springframework.data.jpa.domain.Specification;

public final class BioPageReviewSpecification {

    private BioPageReviewSpecification() {}

    public static Specification<BioPageReview> isOpen() {
        return (root, query, cb) -> root.get("resolvedAt").isNull();
    }

    public static Specification<BioPageReview> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var pattern = LikePatterns.containing(search);
            final var page = root.join("page");
            final var owner = page.join("owner");
            return cb.or(
                    cb.like(cb.lower(page.get("handle")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(page.get("displayName")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(owner.get("email")), pattern, LikePatterns.ESCAPE));
        };
    }

}
