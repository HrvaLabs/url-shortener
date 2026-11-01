package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.security.ApiKeyTokens;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyTest {

    @Test
    void aNewKeyBelongsToItsOwnerAndExpiresWhenAsked() {
        final var user = User.builder().id(1L).build();
        final var expiry = LocalDateTime.of(2027, 1, 30, 10, 0);

        final var token = ApiKeyTokens.generate();

        final var apiKey = new ApiKey(user, expiry, token);

        assertThat(apiKey.getKeyHash()).isEqualTo(token.hash());
        assertThat(apiKey.getKeyPrefix()).isEqualTo(token.prefix());
        assertThat(apiKey.getOwner()).isEqualTo(user);
        assertThat(apiKey.getExpirationDate()).isEqualTo(expiry);
        assertThat(apiKey.getLastUsedAt()).isNull();
    }

    @Test
    void aNewKeyCanNeverExpire() {
        assertThat(new ApiKey(User.builder().id(1L).build(), null, ApiKeyTokens.generate()).getExpirationDate()).isNull();
    }

    @Test
    void theKeyItselfIsNeverPartOfTheEntity() {
        assertThat(java.util.Arrays.stream(ApiKey.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .contains("keyHash", "keyPrefix").doesNotContain("key");
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
