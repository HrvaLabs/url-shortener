package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.User;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

public final class UserSpecification {

    private UserSpecification() {}

    public static Specification<User> search(final String keyword) {
        return (root, query, cb) -> {
            final var pattern = "%" + keyword.toLowerCase() + "%";
            return cb.like(cb.lower(root.get("email")), pattern);
        };
    }

    public static Specification<User> hasActive(final Boolean active) {
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }

    public static Specification<User> idIn(final Collection<Long> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }

}
