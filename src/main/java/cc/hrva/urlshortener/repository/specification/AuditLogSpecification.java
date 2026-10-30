package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import java.time.LocalDate;
import org.springframework.data.jpa.domain.Specification;

public final class AuditLogSpecification {

    private AuditLogSpecification() {}

    public static Specification<AuditLog> searchMatches(final String search) {
        return (root, query, cb) -> {
            final var pattern = LikePatterns.containing(search);
            return cb.or(
                    cb.like(cb.lower(root.get("performedBy")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("targetType")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("targetIdentifier")), pattern, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("details")), pattern, LikePatterns.ESCAPE));
        };
    }

    public static Specification<AuditLog> hasAction(final AuditAction action) {
        return (root, query, cb) -> cb.equal(root.get("action"), action);
    }

    public static Specification<AuditLog> performedOnOrAfter(final LocalDate from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("performedAt"), from.atStartOfDay());
    }

    public static Specification<AuditLog> performedOnOrBefore(final LocalDate to) {
        return (root, query, cb) -> cb.lessThan(root.get("performedAt"), to.plusDays(1).atStartOfDay());
    }

}
