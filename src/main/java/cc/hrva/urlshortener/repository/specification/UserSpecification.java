package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.UserSignInMethod;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.Collection;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

public final class UserSpecification {

    public static final String LINKS = "links";
    public static final String VISITS = "visits";

    private UserSpecification() {}

    public static Specification<User> orderedBy(final Sort sort) {
        return (root, query, cb) -> {
            if (query.getResultType() != Long.class) {
                query.orderBy(sort.stream().map(order -> orderOf(order, root, query, cb)).toList());
            }
            return cb.conjunction();
        };
    }

    private static Order orderOf(final Sort.Order order, final Root<User> root, final CriteriaQuery<?> query, final CriteriaBuilder cb) {
        final Expression<?> expression = switch (order.getProperty()) {
            case LINKS -> ownedLinks(root, query, cb, false);
            case VISITS -> ownedLinks(root, query, cb, true);
            default -> root.get(order.getProperty());
        };
        return order.isAscending() ? cb.asc(expression) : cb.desc(expression);
    }

    private static Expression<Long> ownedLinks(final Root<User> root, final CriteriaQuery<?> query, final CriteriaBuilder cb, final boolean visits) {
        final var owned = query.subquery(Long.class);
        final var url = owned.from(Url.class);
        owned.select(visits ? cb.coalesce(cb.sum(url.<Long>get("visits")), 0L) : cb.count(url)).where(cb.equal(url.get("owner"), root));
        return owned;
    }

    public static Specification<User> search(final String keyword) {
        return (root, query, cb) -> cb.like(cb.lower(root.get("email")), LikePatterns.containing(keyword), LikePatterns.ESCAPE);
    }

    public static Specification<User> hasActive(final Boolean active) {
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }

    public static Specification<User> hasSignInMethod(final SignInMethod method) {
        return (root, query, cb) -> {
            final var used = query.subquery(Long.class);
            final var signInMethod = used.from(UserSignInMethod.class);
            used.select(signInMethod.get("userId"))
                    .where(cb.equal(signInMethod.get("userId"), root.get("id")), cb.equal(signInMethod.get("method"), method));

            return cb.exists(used);
        };
    }

    public static Specification<User> idIn(final Collection<Long> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }

}
