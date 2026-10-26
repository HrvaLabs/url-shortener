package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.BioPage;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.data.jpa.domain.Specification;

public final class BioPageSpecification {

    private static final char LIKE_ESCAPE = '\\';
    private static final Pattern LIKE_SPECIAL = Pattern.compile("[\\\\%_]");

    private BioPageSpecification() {}

    public static Specification<BioPage> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var pattern = "%" + LIKE_SPECIAL.matcher(search.toLowerCase(Locale.ROOT)).replaceAll("\\\\$0") + "%";
            final var owner = root.join("owner");
            return cb.or(
                    cb.like(cb.lower(root.get("handle")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("displayName")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(owner.get("email")), pattern, LIKE_ESCAPE));
        };
    }

    public static Specification<BioPage> isHidden() {
        return (root, query, cb) -> root.get("hiddenAt").isNotNull();
    }

}
