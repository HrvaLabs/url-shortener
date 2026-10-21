package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.SourceKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlocklistSourceTest {

    @Test
    void aNewSourceIsEnabledByDefault() {
        assertThat(BlocklistSource.builder().build().isEnabled()).isTrue();
    }

    @Test
    void aNewSourceIsADomainListRefreshedEverySixHoursWithoutFallbacks() {
        final var source = BlocklistSource.builder().build();

        assertThat(source.getKind()).isEqualTo(SourceKind.DOMAINS);
        assertThat(source.getRefreshIntervalHours()).isEqualTo(6);
        assertThat(source.getFallbackUrls()).isEmpty();
    }

}
