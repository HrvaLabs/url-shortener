package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.LinkDestinationChange;
import org.springframework.data.jpa.domain.Specification;

public final class LinkDestinationChangeSpecification {

    private LinkDestinationChangeSpecification() {}

    public static Specification<LinkDestinationChange> ofLink(final long urlId) {
        return (root, query, cb) -> cb.equal(root.get("urlId"), urlId);
    }

    public static Specification<LinkDestinationChange> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var pattern = LikePatterns.containing(search);
            return cb.or(
                    cb.like(cb.lower(root.get("oldLongUrl")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("newLongUrl")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("changedBy")), pattern, LikePatterns.ESCAPE));
        };
    }

}
