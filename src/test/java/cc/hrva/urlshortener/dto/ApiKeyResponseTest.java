package cc.hrva.urlshortener.dto;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import cc.hrva.urlshortener.model.ApiKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyResponseTest {

    private ApiKey.ApiKeyBuilder baseKey() {
        return ApiKey.builder()
                .id(1L)
                .keyHash("0".repeat(64))
                .keyPrefix("hrva_a1b2c3")
                .apiCallsUsed(10L)
                .createDate(LocalDateTime.now())
                .expirationDate(LocalDateTime.now().plusDays(1));
    }

    @Test
    void shouldBeActive() {
        assertThat(ApiKeyResponse.from(baseKey().active(true).build()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void aKeyThatNeverExpiresIsActive() {
        assertThat(ApiKeyResponse.from(baseKey().active(true).expirationDate(null).build()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void anInactiveKeyWithManyCallsIsRevokedNotLimitReached() {
        final var key = baseKey().active(false).apiCallsUsed(100_000L).build();
        assertThat(ApiKeyResponse.from(key).status()).isEqualTo("REVOKED");
    }

    @Test
    void shouldBeExpired() {
        final var key = baseKey().active(false).expirationDate(LocalDateTime.now().minusDays(1)).build();
        assertThat(ApiKeyResponse.from(key).status()).isEqualTo("EXPIRED");
    }

    @Test
    void shouldBeRevoked() {
        assertThat(ApiKeyResponse.from(baseKey().active(false).build()).status()).isEqualTo("REVOKED");
    }

    @Test
    void anInactiveKeyThatNeverExpiresIsRevoked() {
        assertThat(ApiKeyResponse.from(baseKey().active(false).expirationDate(null).build()).status()).isEqualTo("REVOKED");
    }

    @Test
    void theUsageAndTheLastUseAreShown() {
        final var lastUsed = LocalDateTime.of(2026, 10, 30, 9, 15);

        final var response = ApiKeyResponse.from(baseKey().active(true).apiCallsUsed(7L).lastUsedAt(lastUsed).build());

        assertThat(response.apiCallsUsed()).isEqualTo(7L);
        assertThat(response.lastUsedAt()).isEqualTo(lastUsed);
        assertThat(ApiKeyResponse.from(baseKey().active(true).build()).lastUsedAt()).isNull();
    }

    @Test
    void theResponseHasNoCallLimit() {
        assertThat(Arrays.stream(ApiKeyResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("id", "keyPrefix", "apiCallsUsed", "lastUsedAt", "createDate", "expirationDate", "active", "status", "ownerEmail");
    }

    @Test
    void aListedKeyShowsItsPrefixOnly() {
        assertThat(ApiKeyResponse.from(baseKey().active(true).build()).keyPrefix()).isEqualTo("hrva_a1b2c3");
    }

    @Test
    void theCreateResponseIsTheListedKeyWithTheKeyOnce() {
        final var created = CreatedApiKeyResponse.from(baseKey().active(true).build(), "hrva_a1b2c3secret");

        assertThat(Arrays.stream(CreatedApiKeyResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("id", "key", "keyPrefix", "apiCallsUsed", "lastUsedAt", "createDate", "expirationDate", "active", "status", "ownerEmail");
        assertThat(created.key()).isEqualTo("hrva_a1b2c3secret");
        assertThat(created.keyPrefix()).isEqualTo("hrva_a1b2c3");
        assertThat(created.status()).isEqualTo("ACTIVE");
    }

    @Test
    void theDocumentationDescribesTheRequestCounterAndAFullLengthKey() throws Exception {
        for (final var type : java.util.List.of(ApiKeyResponse.class, CreatedApiKeyResponse.class)) {
            assertThat(schemaOf(type, "apiCallsUsed").description()).isEqualTo("How many requests have been made with the key in total");
            assertThat(schemaOf(type, "lastUsedAt").description()).isEqualTo("When the last request was made with the key; null until its first request");
        }
        assertThat(schemaOf(CreatedApiKeyResponse.class, "key").example()).matches("hrva_[0-9A-Za-z]{43}").hasSize(48);
    }

    private static io.swagger.v3.oas.annotations.media.Schema schemaOf(final Class<?> type, final String component) throws Exception {
        return type.getDeclaredField(component).getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class) != null
                ? type.getDeclaredField(component).getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class)
                : Arrays.stream(type.getRecordComponents()).filter(recordComponent -> recordComponent.getName().equals(component)).findFirst().orElseThrow()
                        .getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
    }
}
