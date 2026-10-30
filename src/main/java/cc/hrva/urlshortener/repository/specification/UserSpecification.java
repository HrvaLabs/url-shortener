package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.UserSignInMethod;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

public final class UserSpecification {

    private UserSpecification() {}

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
