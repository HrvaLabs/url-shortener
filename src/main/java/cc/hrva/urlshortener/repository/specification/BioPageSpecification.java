package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.BioPage;
import org.springframework.data.jpa.domain.Specification;

public final class BioPageSpecification {

    private BioPageSpecification() {}

    public static Specification<BioPage> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var pattern = LikePatterns.containing(search);
            final var owner = root.join("owner");
            return cb.or(
                    cb.like(cb.lower(root.get("handle")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("displayName")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(owner.get("email")), pattern, LikePatterns.ESCAPE));
        };
    }

    public static Specification<BioPage> isHidden() {
        return (root, query, cb) -> root.get("hiddenAt").isNotNull();
    }

}
