package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import jakarta.persistence.criteria.JoinType;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import org.springframework.data.jpa.domain.Specification;

public final class UrlSpecification {

    private static final char LIKE_ESCAPE = '\\';
    private static final Pattern LIKE_SPECIAL = Pattern.compile("[\\\\%_]");

    private UrlSpecification() {}

    public static Specification<Url> hasOwner(final User owner) {
        return (root, query, cb) -> cb.equal(root.get("owner"), owner);
    }

    public static Specification<Url> search(final String keyword) {
        return (root, query, cb) -> {
            final var pattern = containing(keyword);
            return cb.or(
                    cb.like(cb.lower(root.get("longUrl")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("shortUrl")), pattern, LIKE_ESCAPE));
        };
    }

    public static Specification<Url> searchIncludingOwner(final String keyword) {
        return (root, query, cb) -> {
            final var pattern = containing(keyword);
            final var owner = root.join("owner", JoinType.LEFT);
            return cb.or(
                    cb.like(cb.lower(root.get("longUrl")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("shortUrl")), pattern, LIKE_ESCAPE),
                    cb.like(cb.lower(owner.get("email")), pattern, LIKE_ESCAPE));
        };
    }

    public static Specification<Url> hasNoOwner() {
        return (root, query, cb) -> root.get("owner").isNull();
    }

    public static Specification<Url> hasActive(final Boolean active) {
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }

    public static Specification<Url> hasTag(final String tag) {
        return (root, query, cb) -> {
            query.distinct(true);
            return cb.equal(root.join("tags"), tag.trim().toLowerCase());
        };
    }

    public static Specification<Url> isExpired() {
        return (root, query, cb) ->
                cb.and(
                        root.get("expirationDate").isNotNull(),
                        cb.lessThan(root.get("expirationDate"), LocalDateTime.now()));
    }

    public static Specification<Url> createdAfter(final LocalDateTime dateFrom) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createDate"), dateFrom);
    }

    public static Specification<Url> createdBefore(final LocalDateTime dateTo) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("createDate"), dateTo);
    }

    public static Specification<Url> notExpired() {
        return (root, query, cb) ->
                cb.or(
                        root.get("expirationDate").isNull(),
                        cb.greaterThanOrEqualTo(root.get("expirationDate"), LocalDateTime.now()));
    }

    private static String containing(final String keyword) {
        return "%" + LIKE_SPECIAL.matcher(keyword.toLowerCase()).replaceAll("\\\\$0") + "%";
    }

}
