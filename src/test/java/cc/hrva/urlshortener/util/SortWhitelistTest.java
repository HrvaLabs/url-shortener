package cc.hrva.urlshortener.util;

import cc.hrva.urlshortener.exception.InvalidSortException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.data.domain.Sort.Order.asc;
import static org.springframework.data.domain.Sort.Order.desc;

class SortWhitelistTest {

    private static final Sort NEWEST_FIRST = Sort.by(desc("createdAt"), desc("id"));

    private final SortWhitelist whitelist = SortWhitelist.of("domain", "type", "createdAt")
            .byDefault(NEWEST_FIRST)
            .thenBy(desc("createdAt"), desc("id"));

    @Test
    void aPageWithoutASortGetsTheDefaultOrder() {
        assertThat(whitelist.apply(PageRequest.of(2, 50))).isEqualTo(PageRequest.of(2, 50, NEWEST_FIRST));
    }

    @Test
    void aChosenSortKeepsItsDirectionAndBreaksTiesWithTheTieBreakers() {
        assertThat(whitelist.apply(PageRequest.of(1, 20, Sort.by(asc("type")))))
                .isEqualTo(PageRequest.of(1, 20, Sort.by(asc("type"), desc("createdAt"), desc("id"))));
    }

    @Test
    void aTieBreakerAlreadyChosenIsNotAddedAgain() {
        assertThat(whitelist.apply(PageRequest.of(0, 20, Sort.by(asc("createdAt")))))
                .isEqualTo(PageRequest.of(0, 20, Sort.by(asc("createdAt"), desc("id"))));
    }

    @Test
    void anUnpagedRequestStaysUnpagedWithTheOrder() {
        assertThat(whitelist.apply(Pageable.unpaged(Sort.by(desc("domain")))))
                .isEqualTo(Pageable.unpaged(Sort.by(desc("domain"), desc("createdAt"), desc("id"))));
        assertThat(whitelist.apply(Pageable.unpaged())).isEqualTo(Pageable.unpaged(NEWEST_FIRST));
    }

    @Test
    void aFieldOffTheListIsRefusedWithTheListInTheMessage() {
        assertThatThrownBy(() -> whitelist.apply(PageRequest.of(0, 20, Sort.by("createdBy"))))
                .isInstanceOf(InvalidSortException.class)
                .hasMessage("Sort must be domain, type or createdAt");
        assertThatThrownBy(() -> whitelist.apply(PageRequest.of(0, 20, Sort.by("domain", "owner.password"))))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    void theMessageNamesTwoFieldsWithOrAndOneFieldAlone() {
        assertThatThrownBy(() -> SortWhitelist.of("alias", "createdAt").byDefault(NEWEST_FIRST).apply(PageRequest.of(0, 5, Sort.by("id"))))
                .hasMessage("Sort must be alias or createdAt");
        assertThatThrownBy(() -> SortWhitelist.of("attempts").byDefault(NEWEST_FIRST).apply(PageRequest.of(0, 5, Sort.by("id"))))
                .hasMessage("Sort must be attempts");
    }

    @Test
    void aFieldThatMayBeEmptySortsItsEmptyRowsLastInBothDirections() {
        final var withOwners = SortWhitelist.of("owner.email", "createDate").byDefault(NEWEST_FIRST).nullsLast("owner.email");

        assertThat(withOwners.apply(PageRequest.of(0, 20, Sort.by(asc("owner.email"))))).isEqualTo(
                PageRequest.of(0, 20, Sort.by(asc("owner.email").nullsLast())));
        assertThat(withOwners.apply(PageRequest.of(0, 20, Sort.by(desc("owner.email"), asc("createDate"))))).isEqualTo(
                PageRequest.of(0, 20, Sort.by(desc("owner.email").nullsLast(), asc("createDate"))));
    }

    @Test
    void theSortOfARequestCanBeCheckedWithoutAPage() {
        assertThat(whitelist.sortOf(Sort.unsorted())).isEqualTo(NEWEST_FIRST);
        assertThat(whitelist.sortOf(Sort.by(desc("domain")))).isEqualTo(Sort.by(desc("domain"), desc("createdAt"), desc("id")));
    }

}
