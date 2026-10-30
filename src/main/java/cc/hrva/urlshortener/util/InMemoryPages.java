package cc.hrva.urlshortener.util;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

public final class InMemoryPages {

    private InMemoryPages() {}

    public static <T> Page<T> of(final List<T> rows, final Pageable pageable) {
        if (pageable.isUnpaged()) {
            return new PageImpl<>(rows, pageable, rows.size());
        }
        final var from = (int) Math.min(pageable.getOffset(), rows.size());
        final var to = Math.min(from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

}
