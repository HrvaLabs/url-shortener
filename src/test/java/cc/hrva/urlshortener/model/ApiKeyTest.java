package cc.hrva.urlshortener.model;

import java.time.LocalDateTime;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyTest {

    @Mock
    private AppProperties appProperties;

    @Test
    void shouldCreateApiKeyWithDefaults() {
        final var user = User.builder().id(1L).build();
        when(appProperties.getApiKeyCallsLimit()).thenReturn(100L);
        when(appProperties.getApiKeyExpirationInMonths()).thenReturn(3L);

        final var apiKey = new ApiKey(user, appProperties);

        assertThat(apiKey.getKey()).isNotNull();
        assertThat(apiKey.getOwner()).isEqualTo(user);
        assertThat(apiKey.getApiCallsLimit()).isEqualTo(100L);
        assertThat(apiKey.getExpirationDate()).isAfter(LocalDateTime.now());
    }

    @Test
    void shouldDeactivate() {
        final var apiKey = ApiKey.builder().active(true).build();

        apiKey.deactivate();

        assertThat(apiKey.isActive()).isFalse();
    }

    @Test
    void shouldInitializeOnCreate() {
        final var apiKey = new ApiKey();
        apiKey.onCreate();

        assertThat(apiKey.getCreateDate()).isNotNull();
        assertThat(apiKey.getApiCallsUsed()).isEqualTo(0L);
        assertThat(apiKey.isActive()).isTrue();
    }
}
