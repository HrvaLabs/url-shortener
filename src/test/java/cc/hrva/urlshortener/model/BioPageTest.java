package cc.hrva.urlshortener.model;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BioPageTest {

    @Test
    void creatingStampsBothTimesToTheMicrosecond() {
        final var page = new BioPage();

        page.onCreate();

        assertThat(page.getCreatedAt()).isEqualTo(page.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(page.getUpdatedAt()).isEqualTo(page.getCreatedAt());
    }

    @Test
    void updatingStampsUpdatedAtToTheMicrosecond() {
        final var page = BioPage.builder().updatedAt(LocalDateTime.of(2000, 1, 1, 0, 0)).build();

        page.onUpdate();

        assertThat(page.getUpdatedAt()).isAfter(LocalDateTime.of(2000, 1, 1, 0, 0));
        assertThat(page.getUpdatedAt()).isEqualTo(page.getUpdatedAt().truncatedTo(ChronoUnit.MICROS));
    }

}
