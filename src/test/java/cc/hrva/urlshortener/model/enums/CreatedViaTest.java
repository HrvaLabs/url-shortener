package cc.hrva.urlshortener.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreatedViaTest {

    @Test
    void anonymousVariants() {
        assertThat(CreatedVia.WEB.anonymous()).isEqualTo(CreatedVia.ANONYMOUS_WEB);
        assertThat(CreatedVia.MCP.anonymous()).isEqualTo(CreatedVia.ANONYMOUS_MCP);
        assertThat(CreatedVia.API_KEY.anonymous()).isEqualTo(CreatedVia.API_KEY);
        assertThat(CreatedVia.BULK.anonymous()).isEqualTo(CreatedVia.BULK);
        assertThat(CreatedVia.ANONYMOUS_WEB.anonymous()).isEqualTo(CreatedVia.ANONYMOUS_WEB);
        assertThat(CreatedVia.ANONYMOUS_MCP.anonymous()).isEqualTo(CreatedVia.ANONYMOUS_MCP);
    }

}
