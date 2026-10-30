package cc.hrva.urlshortener.util;

import cc.hrva.urlshortener.exception.InvalidSortException;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class SortWhitelist {

    private final List<String> fields;
    private final Sort defaultSort;
    private final List<Sort.Order> tieBreakers;
    private final Set<String> nullsLast;

    private SortWhitelist(final List<String> fields, final Sort defaultSort, final List<Sort.Order> tieBreakers, final Set<String> nullsLast) {
        this.fields = fields;
        this.defaultSort = defaultSort;
        this.tieBreakers = tieBreakers;
        this.nullsLast = nullsLast;
    }

    public static SortWhitelist of(final String... fields) {
        return new SortWhitelist(List.of(fields), Sort.unsorted(), List.of(), Set.of());
    }

    public SortWhitelist byDefault(final Sort sort) {
        return new SortWhitelist(fields, sort, tieBreakers, nullsLast);
    }

    public SortWhitelist thenBy(final Sort.Order... orders) {
        return new SortWhitelist(fields, defaultSort, List.of(orders), nullsLast);
    }

    public SortWhitelist nullsLast(final String... nullableFields) {
        return new SortWhitelist(fields, defaultSort, tieBreakers, Set.of(nullableFields));
    }

    public Pageable apply(final Pageable pageable) {
        final var sort = sortOf(pageable.getSort());
        return pageable.isPaged() ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort) : Pageable.unpaged(sort);
    }

    public Sort sortOf(final Sort requested) {
        if (requested.isUnsorted()) {
            return withTieBreakers(defaultSort);
        }
        if (requested.stream().anyMatch(order -> !fields.contains(order.getProperty()))) {
            throw new InvalidSortException(refusal());
        }
        return withTieBreakers(Sort.by(requested.stream()
                .map(order -> nullsLast.contains(order.getProperty()) ? order.nullsLast() : order)
                .toList()));
    }

    private Sort withTieBreakers(final Sort sort) {
        var result = sort;
        for (final var tieBreaker : tieBreakers) {
            if (result.getOrderFor(tieBreaker.getProperty()) == null) {
                result = result.and(Sort.by(tieBreaker));
            }
        }
        return result;
    }

    private String refusal() {
        if (fields.size() == 1) {
            return "Sort must be " + fields.getFirst();
        }
        return "Sort must be " + String.join(", ", fields.subList(0, fields.size() - 1)) + " or " + fields.getLast();
    }

}
