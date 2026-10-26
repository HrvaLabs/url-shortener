package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.BioPage;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class BioPageSpecification {

    private BioPageSpecification() {}

    public static Specification<BioPage> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var pattern = "%" + search.toLowerCase(Locale.ROOT) + "%";
            final var owner = root.join("owner");
            return cb.or(
                    cb.like(cb.lower(root.get("handle")), pattern),
                    cb.like(cb.lower(root.get("displayName")), pattern),
                    cb.like(cb.lower(owner.get("email")), pattern));
        };
    }

    public static Specification<BioPage> isHidden() {
        return (root, query, cb) -> root.get("hiddenAt").isNotNull();
    }

}
