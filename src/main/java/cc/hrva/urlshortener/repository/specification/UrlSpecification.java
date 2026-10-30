package cc.hrva.urlshortener.repository.specification;

import cc.hrva.urlshortener.model.CampaignLink;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.util.HostName;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.data.jpa.domain.Specification;

public final class UrlSpecification {

    private UrlSpecification() {}

    public static Specification<Url> hasOwner(final User owner) {
        return (root, query, cb) -> cb.equal(root.get("owner"), owner);
    }

    public static Specification<Url> search(final String keyword) {
        return (root, query, cb) -> anyFieldContainsASpellingOf(keyword, cb, List.of(root.get("longUrl"), root.get("shortUrl")));
    }

    public static Specification<Url> searchIncludingOwner(final String keyword) {
        return (root, query, cb) -> {
            final var owner = root.join("owner", JoinType.LEFT);
            return anyFieldContainsASpellingOf(keyword, cb, List.of(root.get("longUrl"), root.get("shortUrl"), owner.get("email")));
        };
    }

    public static Specification<Url> inCampaign(final long campaignId) {
        return (root, query, cb) -> {
            final var subquery = query.subquery(Long.class);
            final var link = subquery.from(CampaignLink.class);
            subquery.select(link.get("id").get("urlId"))
                    .where(cb.equal(link.get("id").get("campaignId"), campaignId), cb.equal(link.get("id").get("urlId"), root.get("id")));
            return cb.exists(subquery);
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
            return cb.equal(root.join("tags"), tag.trim().toLowerCase(Locale.ROOT));
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

    public static Specification<Url> hasDeadDestination() {
        return (root, query, cb) -> cb.and(root.get("destinationDeadSince").isNotNull(), cb.isTrue(root.get("active")));
    }

    public static Specification<Url> notExpired() {
        return (root, query, cb) ->
                cb.or(
                        root.get("expirationDate").isNull(),
                        cb.greaterThanOrEqualTo(root.get("expirationDate"), LocalDateTime.now()));
    }

    static Predicate anyFieldContainsASpellingOf(final String keyword, final CriteriaBuilder cb, final List<Expression<String>> fields) {
        return cb.or(spellingsOf(keyword).stream()
                .map(LikePatterns::containing)
                .flatMap(pattern -> fields.stream().map(field -> cb.like(cb.lower(field), pattern, LikePatterns.ESCAPE)))
                .toArray(Predicate[]::new));
    }

    private static List<String> spellingsOf(final String keyword) {
        final var term = keyword.toLowerCase(Locale.ROOT);
        return DomainNames.hostNameOf(term.strip())
                .filter(host -> !host.ascii().equals(host.unicode()) && DomainNames.HOST_NAME.matcher(host.ascii()).matches())
                .map(host -> Stream.concat(Stream.of(keyword), otherSpellingsOf(term, host).stream()).toList())
                .orElseGet(() -> List.of(keyword));
    }

    private static List<String> otherSpellingsOf(final String term, final HostName host) {
        if (term.contains(host.unicode())) {
            return List.of(term.replace(host.unicode(), host.ascii()));
        }
        if (term.contains(host.ascii())) {
            return List.of(term.replace(host.ascii(), host.unicode()));
        }
        return List.of(host.ascii(), host.unicode());
    }

}
