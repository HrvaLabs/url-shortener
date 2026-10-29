package cc.hrva.urlshortener.dto;

import java.time.LocalDateTime;
import java.util.List;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ThreatType;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class UrlResponseTest {

    private Url.UrlBuilder baseUrl() {
        return Url.builder()
                .id(1L)
                .longUrl("https://example.com")
                .shortUrl("abc")
                .createDate(LocalDateTime.now())
                .expirationDate(LocalDateTime.now().plusDays(1))
                .visits(5L)
                .visitLimit(100L);
    }

    @Test
    void cachedResponsesSurviveTheRedisRoundTrip() {
        final var mapper = JsonMapper.builder().build();
        final var serializer = new GenericJacksonJsonRedisSerializer(mapper);
        final var response = UrlResponse.from(baseUrl().active(true).build());

        final var cached = serializer.deserialize(serializer.serialize(response));

        assertThat(mapper.convertValue(cached, UrlResponse.class)).isEqualTo(response);
    }

    @Test
    void responsesCachedBeforeSuspiciousReasonsExistedStillLoad() {
        final var mapper = JsonMapper.builder().build();
        final var serializer = new GenericJacksonJsonRedisSerializer(mapper);
        final var legacyJson = "{\"id\":1,\"longUrl\":\"https://example.com\",\"shortUrl\":\"abc\",\"visits\":5,"
                + "\"active\":true,\"status\":\"ACTIVE\",\"tags\":[],\"passwordProtected\":false,\"ownerEmail\":null}";

        final var cached = mapper.convertValue(serializer.deserialize(legacyJson.getBytes()), UrlResponse.class);

        assertThat(cached.shortUrl()).isEqualTo("abc");
        assertThat(cached.active()).isTrue();
    }

    @Test
    void omitsSuspiciousReasonsWhenThereAreNone() {
        final var json = JsonMapper.builder().build().writeValueAsString(UrlResponse.from(baseUrl().active(true).build()));

        assertThat(json).doesNotContain("suspiciousReasons");
    }

    @Test
    void blockReasonSurvivesTheRedisRoundTrip() {
        final var mapper = JsonMapper.builder().build();
        final var serializer = new GenericJacksonJsonRedisSerializer(mapper);
        final var response = UrlResponse.from(baseUrl().active(false).blockReason(BlockReason.BLOCKLIST).build())
                .withBlockReason(BlockReason.BLOCKLIST);

        final var cached = serializer.deserialize(serializer.serialize(response));

        assertThat(mapper.convertValue(cached, UrlResponse.class)).isEqualTo(response);
    }

    @Test
    void omitsBlockReasonWhenThereIsNone() {
        final var json = JsonMapper.builder().build().writeValueAsString(UrlResponse.from(baseUrl().active(true).build()));

        assertThat(json).doesNotContain("blockReason");
    }

    @Test
    void keepsTheBlockReasonOutOfOwnerAndPublicResponses() {
        final var url = baseUrl().active(false).blockReason(BlockReason.BLOCKLIST).build();

        assertThat(UrlResponse.from(url).blockReason()).isNull();
        assertThat(UrlResponse.from(url).withBlockReason(BlockReason.BLOCKLIST).publicView().blockReason()).isNull();
    }

    @Test
    void keepsTheModerationDetailsOutOfOwnerAndPublicResponses() {
        final var url = baseUrl().active(false).blockReason(BlockReason.BLOCKLIST).finalUrl("https://example.com/landing").build();

        final var json = JsonMapper.builder().build().writeValueAsString(UrlResponse.from(url));
        final var publicView = UrlResponse.from(url).withModeration(true, true, "https://example.com/landing").publicView();

        assertThat(json).doesNotContain("reviewOpen").doesNotContain("ownerPossibleScammer").doesNotContain("finalUrl");
        assertThat(publicView.reviewOpen()).isNull();
        assertThat(publicView.ownerPossibleScammer()).isNull();
        assertThat(publicView.finalUrl()).isNull();
    }

    @Test
    void anAdminRowSpellsOutFalseModerationDetailsAndLeavesOutAMissingFinalUrl() {
        final var json = JsonMapper.builder().build().writeValueAsString(UrlResponse.from(baseUrl().active(true).build()).withModeration(false, false, null));

        assertThat(json).contains("\"reviewOpen\":false").contains("\"ownerPossibleScammer\":false").doesNotContain("finalUrl");
    }

    @Test
    void moderationDetailsSurviveTheRedisRoundTrip() {
        final var mapper = JsonMapper.builder().build();
        final var serializer = new GenericJacksonJsonRedisSerializer(mapper);
        final var response = UrlResponse.from(baseUrl().active(true).build()).withModeration(true, false, "https://example.com/landing");

        final var cached = serializer.deserialize(serializer.serialize(response));

        assertThat(mapper.convertValue(cached, UrlResponse.class)).isEqualTo(response);
    }

    @Test
    void shouldBeActive() {
        assertThat(UrlResponse.from(baseUrl().active(true).build()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldBeBlockedWhenThreatPresent() {
        final var url = baseUrl().active(false).threatType(ThreatType.MALWARE).build();
        assertThat(UrlResponse.from(url).status()).isEqualTo("BLOCKED");
    }

    @Test
    void shouldBeBlockedWhenBlockReasonPresent() {
        final var url = baseUrl().active(false).blockReason(BlockReason.DOMAIN_RULE).build();
        assertThat(UrlResponse.from(url).status()).isEqualTo("BLOCKED");
    }

    @Test
    void shouldBeLimitReached() {
        final var url = baseUrl().active(false).visits(100L).build();
        assertThat(UrlResponse.from(url).status()).isEqualTo("LIMIT_REACHED");
    }

    @Test
    void shouldBeExpired() {
        final var url = baseUrl().active(false).expirationDate(LocalDateTime.now().minusDays(1)).build();
        assertThat(UrlResponse.from(url).status()).isEqualTo("EXPIRED");
    }

    @Test
    void shouldBeDeactivated() {
        assertThat(UrlResponse.from(baseUrl().active(false).build()).status()).isEqualTo("DEACTIVATED");
    }

    @Test
    void shouldIncludeTags() {
        final var url = baseUrl().active(true).tags(new java.util.LinkedHashSet<>(java.util.List.of("work"))).build();
        assertThat(UrlResponse.from(url).tags()).containsExactly("work");
    }

    @Test
    void shouldDefaultTagsToEmptyWhenNull() {
        assertThat(UrlResponse.from(baseUrl().active(true).build()).tags()).isEmpty();
    }

    @Test
    void shouldFlagPasswordProtected() {
        assertThat(UrlResponse.from(baseUrl().active(true).passwordHash("hash").build()).passwordProtected()).isTrue();
        assertThat(UrlResponse.from(baseUrl().active(true).build()).passwordProtected()).isFalse();
    }

    @Test
    void fromCarriesDestinationHealth() {
        final var deadSince = LocalDateTime.of(2026, 10, 27, 2, 11, 40);
        final var response = UrlResponse.from(baseUrl().active(true).destinationDeadSince(deadSince).destinationLastStatus("404")
                .destinationFailStreak(3).build());

        assertThat(response.destinationHealth()).isEqualTo(new DestinationHealth(deadSince, "404"));
        assertThat(JsonMapper.builder().build().writeValueAsString(response))
                .contains("\"destinationHealth\":{\"deadSince\":\"2026-10-27T02:11:40\",\"lastStatus\":\"404\"}");
    }

    @Test
    void publicViewHasNoDestinationHealth() {
        final var response = UrlResponse.from(baseUrl().active(true).destinationDeadSince(LocalDateTime.of(2026, 10, 27, 2, 11))
                .destinationLastStatus("nxdomain").build());

        assertThat(response.publicView().destinationHealth()).isNull();
        assertThat(JsonMapper.builder().build().writeValueAsString(response.publicView())).doesNotContain("destinationHealth");
    }

    @Test
    void liveLinkHasNone() {
        final var failingButNotDead = UrlResponse.from(baseUrl().active(true).destinationFailStreak(2).destinationLastStatus("404").build());

        assertThat(failingButNotDead.destinationHealth()).isNull();
        assertThat(JsonMapper.builder().build().writeValueAsString(failingButNotDead)).doesNotContain("destinationHealth");
        assertThat(DestinationHealth.of(baseUrl().build())).isNull();
    }

    @Test
    void theAdminDecorationsKeepTheDestinationHealth() {
        final var health = new DestinationHealth(LocalDateTime.of(2026, 10, 27, 2, 11), "410");
        final var response = UrlResponse.from(baseUrl().active(true).destinationDeadSince(health.deadSince())
                .destinationLastStatus("410").build());

        assertThat(response.withSuspiciousReasons(List.of("x")).destinationHealth()).isEqualTo(health);
        assertThat(response.withBlockReason(BlockReason.ADMIN).destinationHealth()).isEqualTo(health);
        assertThat(response.withModeration(true, false, null).destinationHealth()).isEqualTo(health);
    }

    @Test
    void destinationHealthSurvivesTheRedisRoundTrip() {
        final var mapper = JsonMapper.builder().build();
        final var serializer = new GenericJacksonJsonRedisSerializer(mapper);
        final var response = UrlResponse.from(baseUrl().active(true).destinationDeadSince(LocalDateTime.of(2026, 10, 27, 2, 11))
                .destinationLastStatus("tls").build());

        assertThat(mapper.convertValue(serializer.deserialize(serializer.serialize(response)), UrlResponse.class)).isEqualTo(response);
    }

}
