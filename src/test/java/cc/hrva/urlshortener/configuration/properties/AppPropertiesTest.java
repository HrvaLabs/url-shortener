package cc.hrva.urlshortener.configuration.properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesTest {

    private static final String KEY_MATERIAL = "private-key-material";

    @Test
    void toStringNeverContainsTheMcpSigningKey() {
        final var appProperties = new AppProperties();
        appProperties.getMcp().setIssuer("https://hrva.cc");
        appProperties.getMcp().setSigningKey("{\"kty\":\"RSA\",\"d\":\"" + KEY_MATERIAL + "\"}");

        assertThat(appProperties.getMcp().toString())
                .contains("https://hrva.cc")
                .doesNotContain(KEY_MATERIAL);
        assertThat(appProperties.toString()).doesNotContain(KEY_MATERIAL);
    }

}
