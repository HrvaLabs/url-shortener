package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.LinkReview;
import jakarta.persistence.criteria.JoinType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class LinkReviewSpecification {

    private LinkReviewSpecification() {}

    public static Specification<LinkReview> isOpen() {
        return (root, query, cb) -> root.get("resolvedAt").isNull();
    }

    public static Specification<LinkReview> reasonContains(final String reason) {
        return (root, query, cb) -> cb.like(cb.lower(root.get("reasons")), LikePatterns.containing(reason), LikePatterns.ESCAPE);
    }

    public static Specification<LinkReview> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var url = root.join("url");
            final var owner = url.join("owner", JoinType.LEFT);
            return UrlSpecification.anyFieldContainsASpellingOf(search, cb, List.of(url.get("longUrl"), url.get("shortUrl"), owner.get("email")));
        };
    }

    public static Specification<LinkReview> linkCreatedBefore(final LocalDateTime cutoff) {
        return (root, query, cb) -> cb.lessThan(root.get("url").get("createDate"), cutoff);
    }

}
