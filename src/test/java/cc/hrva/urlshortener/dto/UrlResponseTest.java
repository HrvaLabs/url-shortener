package cc.hrva.urlshortener.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import cc.hrva.urlshortener.model.CustomDomain;
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
    void asCreatedLooksLikeTheSameLinkActive() {
        final var held = baseUrl().active(false).blockReason(BlockReason.CAMPAIGN).blockDetail("/wqamhcsom?r&t").blockedAt(LocalDateTime.now()).build();
        final var active = baseUrl().createDate(held.getCreateDate()).expirationDate(held.getExpirationDate()).active(true).build();

        final var created = UrlResponse.from(held).asCreated();

        assertThat(created.active()).isTrue();
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.blockReason()).isNull();
        assertThat(created).isEqualTo(UrlResponse.from(active));
        assertThat(UrlResponse.from(held).withBlockReason(BlockReason.CAMPAIGN).asCreated().blockReason()).isNull();
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

    @Test
    void domainIsAbsentForHrvaLinks() {
        final var response = UrlResponse.from(baseUrl().active(true).build());

        assertThat(response.domain()).isNull();
        assertThat(JsonMapper.builder().build().writeValueAsString(response)).doesNotContain("\"domain\"").doesNotContain("cacheKey");
    }

    @Test
    void aDomainLinkCarriesItsIdAndHostname() {
        final var response = UrlResponse.from(baseUrl().active(true).domain(goExampleCom()).build());

        assertThat(response.domain()).isEqualTo(new DomainRef(7L, "go.example.com"));
        assertThat(JsonMapper.builder().build().writeValueAsString(response))
                .contains("\"domain\":{\"id\":7,\"hostname\":\"go.example.com\"}")
                .doesNotContain("cacheKey");
    }

    @Test
    void publicViewKeepsTheDomain() {
        final var response = UrlResponse.from(baseUrl().active(true).domain(goExampleCom()).build());

        assertThat(response.publicView().domain()).isEqualTo(new DomainRef(7L, "go.example.com"));
    }

    @Test
    void theAdminDecorationsKeepTheDomain() {
        final var response = UrlResponse.from(baseUrl().active(true).domain(goExampleCom()).build());

        assertThat(response.withSuspiciousReasons(List.of("x")).domain()).isEqualTo(response.domain());
        assertThat(response.withBlockReason(BlockReason.ADMIN).domain()).isEqualTo(response.domain());
        assertThat(response.withModeration(true, false, null).domain()).isEqualTo(response.domain());
    }

    @Test
    void theShortConstructorLeavesTheDomainOut() {
        assertThat(new UrlResponse(1L, "https://example.com", "abc", null, null, 0L, null, true, "ACTIVE", Set.of(), false, null).domain())
                .isNull();
    }

    @Test
    void cacheKeyFollowsTheDomain() {
        assertThat(UrlResponse.from(baseUrl().shortUrl("menu").active(true).build()).cacheKey()).isEqualTo("menu");
        assertThat(UrlResponse.from(baseUrl().shortUrl("menu").active(true).domain(goExampleCom()).build()).cacheKey()).isEqualTo("7/menu");
    }

    @Test
    void theDomainSurvivesTheRedisRoundTrip() {
        final var mapper = JsonMapper.builder().build();
        final var serializer = new GenericJacksonJsonRedisSerializer(mapper);
        final var response = UrlResponse.from(baseUrl().active(true).domain(goExampleCom()).build());

        assertThat(mapper.convertValue(serializer.deserialize(serializer.serialize(response)), UrlResponse.class)).isEqualTo(response);
    }

    @Test
    void theWarningPageIsTrueWhenSetAndLeftOutOtherwise() {
        final var flagged = UrlResponse.from(baseUrl().active(true).warningPage(true).build());
        final var plain = UrlResponse.from(baseUrl().active(true).build());

        assertThat(flagged.warningPage()).isTrue();
        assertThat(plain.warningPage()).isNull();
        assertThat(JsonMapper.builder().build().writeValueAsString(flagged)).contains("\"warningPage\":true");
        assertThat(JsonMapper.builder().build().writeValueAsString(plain)).doesNotContain("warningPage");
    }

    @Test
    void publicViewDropsTheFlag() {
        final var response = UrlResponse.from(baseUrl().active(true).warningPage(true).build());

        assertThat(response.publicView().warningPage()).isNull();
        assertThat(JsonMapper.builder().build().writeValueAsString(response.publicView())).doesNotContain("warningPage");
    }

    @Test
    void theAdminDecorationsKeepTheWarningPage() {
        final var response = UrlResponse.from(baseUrl().active(true).warningPage(true).build());

        assertThat(response.withSuspiciousReasons(List.of("x")).warningPage()).isTrue();
        assertThat(response.withBlockReason(BlockReason.ADMIN).warningPage()).isTrue();
        assertThat(response.withModeration(true, false, null).warningPage()).isTrue();
    }

    @Test
    void asCreatedLeavesTheWarningPageOut() {
        assertThat(UrlResponse.from(baseUrl().active(false).warningPage(true).build()).asCreated().warningPage()).isNull();
    }

    @Test
    void theShortConstructorLeavesTheWarningPageOut() {
        assertThat(new UrlResponse(1L, "https://example.com", "abc", null, null, 0L, null, true, "ACTIVE", Set.of(), false, null).warningPage())
                .isNull();
    }

    @Test
    void theWarningPageSurvivesTheRedisRoundTrip() {
        final var mapper = JsonMapper.builder().build();
        final var serializer = new GenericJacksonJsonRedisSerializer(mapper);
        final var response = UrlResponse.from(baseUrl().active(true).warningPage(true).build());

        assertThat(mapper.convertValue(serializer.deserialize(serializer.serialize(response)), UrlResponse.class)).isEqualTo(response);
    }

    @Test
    void aResponseCachedBeforeTheWarningPageExistedReadsAsNoPage() {
        final var mapper = JsonMapper.builder().build();
        final var serializer = new GenericJacksonJsonRedisSerializer(mapper);
        final var legacyJson = "{\"id\":1,\"longUrl\":\"https://example.com\",\"shortUrl\":\"abc\",\"visits\":5,"
                + "\"active\":true,\"status\":\"ACTIVE\",\"tags\":[],\"passwordProtected\":false,\"ownerEmail\":null}";

        assertThat(mapper.convertValue(serializer.deserialize(legacyJson.getBytes()), UrlResponse.class).warningPage()).isNull();
    }

    private static CustomDomain goExampleCom() {
        return CustomDomain.builder().id(7L).hostname("go.example.com").build();
    }

}
