package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.LinkReview;
import java.time.LocalDateTime;
import org.springframework.data.jpa.domain.Specification;

public final class LinkReviewSpecification {

    private LinkReviewSpecification() {}

    public static Specification<LinkReview> isOpen() {
        return (root, query, cb) -> root.get("resolvedAt").isNull();
    }

    public static Specification<LinkReview> reasonContains(final String reason) {
        return (root, query, cb) -> cb.like(cb.lower(root.get("reasons")), LikePatterns.containing(reason), LikePatterns.ESCAPE);
    }

    public static Specification<LinkReview> linkCreatedBefore(final LocalDateTime cutoff) {
        return (root, query, cb) -> cb.lessThan(root.get("url").get("createDate"), cutoff);
    }

}
