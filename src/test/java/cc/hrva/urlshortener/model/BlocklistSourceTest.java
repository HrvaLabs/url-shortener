package cc.hrva.urlshortener.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlocklistSourceTest {

    @Test
    void aNewSourceIsEnabledByDefault() {
        assertThat(BlocklistSource.builder().build().isEnabled()).isTrue();
    }

}
