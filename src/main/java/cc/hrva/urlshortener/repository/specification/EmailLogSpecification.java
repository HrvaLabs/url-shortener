package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.EmailLog;
import org.springframework.data.jpa.domain.Specification;

public final class EmailLogSpecification {

    private EmailLogSpecification() {}

    public static Specification<EmailLog> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var pattern = LikePatterns.containing(search);
            return cb.or(
                    cb.like(cb.lower(root.get("recipient")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("subject")), pattern, LikePatterns.ESCAPE));
        };
    }

    public static Specification<EmailLog> hasStatus(final String status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

}
