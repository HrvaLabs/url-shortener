package cc.hrva.urlshortener.util;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPagesTest {

    private static final List<String> ROWS = List.of("a", "b", "c", "d", "e");

    @Test
    void aPageHoldsItsShareOfTheRowsAndTheTotal() {
        final var page = InMemoryPages.of(ROWS, PageRequest.of(1, 2));

        assertThat(page.getContent()).containsExactly("c", "d");
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getPageable()).isEqualTo(PageRequest.of(1, 2));
    }

    @Test
    void theLastPageHoldsWhatIsLeftAndAPagePastTheEndIsEmpty() {
        assertThat(InMemoryPages.of(ROWS, PageRequest.of(2, 2)).getContent()).containsExactly("e");
        assertThat(InMemoryPages.of(ROWS, PageRequest.of(7, 2)).getContent()).isEmpty();
        assertThat(InMemoryPages.of(ROWS, PageRequest.of(7, 2)).getTotalElements()).isEqualTo(5);
    }

    @Test
    void anUnpagedRequestGetsEveryRow() {
        assertThat(InMemoryPages.of(ROWS, Pageable.unpaged()).getContent()).containsExactlyElementsOf(ROWS);
    }

}
