package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.UrlService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.validation.Validation;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinkToolsTest {

    private static final User CURRENT_USER = User.builder().id(7L).email("user@localhost").active(true).build();
    private static final String RETENTION_SENTENCE =
            "Visitor details are kept for about 48 hours, so the recent figures cover roughly the last two days.";

    @Mock
    private UrlService urlService;

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private McpUserResolver mcpUserResolver;

    private LinkTools linkTools;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.getMcp().setIssuer("https://hrva.cc");
        final var validator = Validation.buildDefaultValidatorFactory().getValidator();
        linkTools = new LinkTools(urlService, appProperties, mcpUserResolver, analyticsService, validator);
    }

    @Test
    void listsOnlyCurrentUsersLinksWithFullShortUrls() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(
                link("abc12", "https://example.com/first", 3L, "ACTIVE"),
                link("xyz89", "https://example.org/second", 0L, "EXPIRED")));

        final var result = linkTools.listLinks(null, null, null);

        assertThat(result.isError()).isFalse();
        assertThat(text(result).lines()).containsExactly(
                "https://hrva.cc/abc12 -> https://example.com/first | 3 visits | ACTIVE",
                "https://hrva.cc/xyz89 -> https://example.org/second | 0 visits | EXPIRED");
        final var order = inOrder(mcpUserResolver, urlService);
        order.verify(mcpUserResolver).currentUser();
        order.verify(urlService).getAllMyUrls(isNull(), any(), any());
        order.verifyNoMoreInteractions();
    }

    @Test
    void filtersByTag() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link("abc12", "https://example.com/first", 1L, "ACTIVE")));

        linkTools.listLinks("example", "work", null);

        final var search = ArgumentCaptor.forClass(UrlSearchDto.class);
        verify(urlService).getAllMyUrls(isNull(), any(), search.capture());
        assertThat(search.getValue()).isEqualTo(new UrlSearchDto("example", null, null, "work", null, null, null));
    }

    @Test
    void pageSizeIs20() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page());

        linkTools.listLinks(null, null, null);
        linkTools.listLinks(null, null, 2);

        final var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(urlService, times(2)).getAllMyUrls(isNull(), pageable.capture(), any());
        assertThat(pageable.getAllValues())
                .extracting(Pageable::getPageNumber, Pageable::getPageSize, Pageable::getSort)
                .containsExactly(
                        tuple(0, 20, Sort.by(Sort.Direction.DESC, "createDate")),
                        tuple(2, 20, Sort.by(Sort.Direction.DESC, "createDate")));
    }

    @Test
    void emptyPageSaysSo() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page());

        final var result = linkTools.listLinks("nothing", null, 5);

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo("No links found.");
    }

    @Test
    void inactiveUserGetsTheUserFacingMessageOnce() {
        when(mcpUserResolver.currentUser()).thenThrow(new UserNotFoundException("User is inactive, please contact administrator"));

        final var result = linkTools.listLinks(null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("User is inactive, please contact administrator");
        verifyNoInteractions(urlService);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void unexpectedFailureReturnsGenericErrorAndLogsNoDetails(final CapturedOutput output) {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("Key (search)=(secret-search-term) violates something"));

        final var result = linkTools.listLinks("secret-search-term", null, null);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("Something went wrong on hrva.cc. Try again in a moment.");
        assertThat(output)
                .contains("tool=list_links")
                .doesNotContain("secret-search-term");
    }

    @Test
    void shortenAddsAiTagOnce() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any())).thenReturn(created("abc12", null, null));

        linkTools.shortenUrl("https://example.com/page", null, null, null, List.of("ai", "work"));
        linkTools.shortenUrl("https://example.com/page", null, null, null, List.of("Work", "AI", "work"));
        linkTools.shortenUrl("https://example.com/page", null, null, null, null);

        final var request = ArgumentCaptor.forClass(CreateUrlDto.class);
        verify(urlService, times(3)).saveUrlRouting(request.capture(), isNull());
        assertThat(request.getAllValues().get(0).getTags()).containsExactly("ai", "work");
        assertThat(request.getAllValues().get(1).getTags()).containsExactly("ai", "work");
        assertThat(request.getAllValues().get(2).getTags()).containsExactly("ai");
    }

    @Test
    void shortenKeepsAiTagWhenTheTagLimitIsReached() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any())).thenReturn(created("abc12", null, null));
        final var tenTags = IntStream.rangeClosed(1, 10).mapToObj(index -> "tag" + index).toList();

        linkTools.shortenUrl("https://example.com/page", null, null, null, tenTags);

        final var request = ArgumentCaptor.forClass(CreateUrlDto.class);
        verify(urlService).saveUrlRouting(request.capture(), isNull());
        assertThat(request.getValue().getTags()).hasSize(10).contains("ai");
    }

    @Test
    void shortenPassesAliasLimitAndExpiry() {
        final var expiry = systemTime("2030-06-30T21:59:00Z");
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any())).thenReturn(created("menu", 100L, expiry));

        final var result = linkTools.shortenUrl("https://example.com/menu", "menu", 100L, "2030-06-30T23:59:00+02:00", List.of("food"));

        final var request = ArgumentCaptor.forClass(CreateUrlDto.class);
        final var order = inOrder(mcpUserResolver, urlService);
        order.verify(mcpUserResolver).currentUser();
        order.verify(urlService).saveUrlRouting(request.capture(), isNull());
        assertThat(request.getValue().getLongUrl()).isEqualTo("https://example.com/menu");
        assertThat(request.getValue().getShortUrl()).isEqualTo("menu");
        assertThat(request.getValue().getVisitLimit()).isEqualTo(100L);
        assertThat(request.getValue().getExpirationDate()).isEqualTo(expiry);
        assertThat(request.getValue().getPassword()).isNull();
        assertThat(result.isError()).isFalse();
        assertThat(text(result).lines()).containsExactly(
                "Short link: https://hrva.cc/menu",
                "Destination: https://example.com/menu",
                "Visit limit: 100",
                "Expires: 2030-06-30T21:59:00Z");
    }

    @Test
    void shortenAcceptsUtcAndOffsetExpiry() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any())).thenReturn(created("abc12", null, null));

        linkTools.shortenUrl("https://example.com", null, null, "2030-06-30T21:59:00Z", null);
        linkTools.shortenUrl("https://example.com", null, null, " 2030-06-30T23:59:00+02:00 ", null);
        linkTools.shortenUrl("https://example.com", null, null, "2030-06-30T23:59:00+02:00[Europe/Zagreb]", null);

        final var request = ArgumentCaptor.forClass(CreateUrlDto.class);
        verify(urlService, times(3)).saveUrlRouting(request.capture(), isNull());
        assertThat(request.getAllValues())
                .extracting(CreateUrlDto::getExpirationDate)
                .containsOnly(systemTime("2030-06-30T21:59:00Z"));
    }

    @Test
    void shortenRejectsUnreadableExpiryWithoutCreatingTheLink() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);

        for (final var expiresAt : List.of("next friday", "2030-06-30T23:59:00", "2030-13-01T00:00:00Z", "2030-06-30")) {
            final var result = linkTools.shortenUrl("https://example.com", null, null, expiresAt, null);

            assertThat(result.isError()).as(expiresAt).isTrue();
            assertThat(text(result)).isEqualTo("expiresAt must be an ISO-8601 date-time with a time zone, "
                    + "for example 2026-12-31T23:59:00Z or 2026-12-31T23:59:00+01:00.");
        }
        verify(urlService, never()).saveUrlRouting(any(), any());
    }

    @Test
    void shortenWithoutOptionsReportsOnlyTheLink() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any())).thenReturn(created("abc12", null, null));

        final var result = linkTools.shortenUrl("https://example.com/page", " ", null, "", null);

        final var request = ArgumentCaptor.forClass(CreateUrlDto.class);
        verify(urlService).saveUrlRouting(request.capture(), isNull());
        assertThat(request.getValue().getShortUrl()).isNull();
        assertThat(request.getValue().getExpirationDate()).isNull();
        assertThat(text(result).lines()).containsExactly(
                "Short link: https://hrva.cc/abc12",
                "Destination: https://example.com/page");
    }

    @Test
    void shortenAppliesTheAliasRulesBeforeCreating() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);

        final var invalidCharacters = linkTools.shortenUrl("https://example.com", "no spaces!", null, null, null);
        final var tooLong = linkTools.shortenUrl("https://example.com", "a".repeat(33), null, null, null);
        final var missingUrl = linkTools.shortenUrl(" ", null, null, null, null);

        assertThat(invalidCharacters.isError()).isTrue();
        assertThat(text(invalidCharacters)).isEqualTo("Custom short URL can only contain letters, numbers, hyphens and underscores");
        assertThat(tooLong.isError()).isTrue();
        assertThat(text(tooLong)).isEqualTo("Custom short URL must be 32 characters or fewer");
        assertThat(missingUrl.isError()).isTrue();
        assertThat(text(missingUrl)).isEqualTo("Long URL is required");
        verify(urlService, never()).saveUrlRouting(any(), any());
    }

    @Test
    void shortenReturnsToolErrorWithBackendMessageWhenAliasTaken() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any())).thenThrow(new ShortUrlAlreadyExistsException("This short URL is already taken. Try another."));

        final var result = linkTools.shortenUrl("https://example.com/menu", "menu", null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("This short URL is already taken. Try another.");
    }

    @Test
    void shortenReturnsToolErrorWithBackendMessageWhenUrlIsUnsafe() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any())).thenThrow(new UrlValidationException("This URL was flagged as unsafe and cannot be shortened"));

        final var result = linkTools.shortenUrl("https://malware.example", null, null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("This URL was flagged as unsafe and cannot be shortened");
    }

    @Test
    void shortenReturnsToolErrorWithTheRefusalWhenTheAccountHitsTheCreateLimit() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), isNull()))
                .thenThrow(new RateLimitExceededException("You've created a lot of links in a short time. Try again in an hour."));

        final var result = linkTools.shortenUrl("https://example.com/menu", null, null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("You've created a lot of links in a short time. Try again in an hour.");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shortenLogsIdsOnly(final CapturedOutput output) {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any())).thenReturn(new UrlResponse(11L, "https://secret-destination.example/path", "secret-alias",
                LocalDateTime.now(), null, 0L, null, true, "ACTIVE", Set.of("ai", "secret-tag"), false, "user@localhost"));

        linkTools.shortenUrl("https://secret-destination.example/path", "secret-alias", null, null, List.of("secret-tag"));

        assertThat(output)
                .contains("MCP shorten_url userId=7 urlId=11")
                .doesNotContain("secret-destination.example")
                .doesNotContain("secret-alias")
                .doesNotContain("secret-tag");
    }

    @Test
    void statsPicksExactAliasAmongNearMatches() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(
                link(31L, "menu-2", true),
                link(32L, "mymenu", true),
                link(34L, "MENU", true),
                link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L)).thenReturn(analytics(33L, "menu"));

        final var result = linkTools.linkStats("menu");

        assertThat(result.isError()).isFalse();
        verify(analyticsService).getUrlAnalyticsForOwner(33L);
        final var pageable = ArgumentCaptor.forClass(Pageable.class);
        final var search = ArgumentCaptor.forClass(UrlSearchDto.class);
        verify(urlService).getAllMyUrls(isNull(), pageable.capture(), search.capture());
        assertThat(pageable.getValue().isUnpaged()).isTrue();
        assertThat(search.getValue()).isEqualTo(new UrlSearchDto("menu", null, null, null, null, null, null));
    }

    @Test
    void statsPrefersTheActiveLinkWhenAnAliasWasReused() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(
                link(40L, "menu", false),
                link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L)).thenReturn(analytics(33L, "menu"));

        linkTools.linkStats("menu");

        verify(analyticsService).getUrlAnalyticsForOwner(33L);
    }

    @Test
    void statsAcceptsHostAndUrlForms() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L)).thenReturn(analytics(33L, "menu"));

        for (final var form : List.of("menu", "hrva.cc/menu", "https://hrva.cc/menu", " HTTP://HRVA.CC/menu/ ", "/menu")) {
            assertThat(linkTools.linkStats(form).isError()).as(form).isFalse();
        }

        final var search = ArgumentCaptor.forClass(UrlSearchDto.class);
        verify(urlService, times(5)).getAllMyUrls(isNull(), any(), search.capture());
        assertThat(search.getAllValues()).extracting(UrlSearchDto::getSearch).containsOnly("menu");
        verify(analyticsService, times(5)).getUrlAnalyticsForOwner(33L);
    }

    @Test
    void statsUnknownAliasReturnsToolError() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(31L, "menu-2", true)));

        final var result = linkTools.linkStats("https://hrva.cc/menu");

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("No link hrva.cc/menu in your account.");
        verifyNoInteractions(analyticsService);
    }

    @Test
    void statsWithoutAnAliasAsksForOne() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);

        final var result = linkTools.linkStats(" https://hrva.cc/ ");

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("Give the short link, for example menu, hrva.cc/menu or https://hrva.cc/menu.");
        verifyNoInteractions(urlService, analyticsService);
    }

    @Test
    void statsTextIncludes48HourSentence() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L)).thenReturn(analytics(33L, "menu"));

        final var result = linkTools.linkStats("menu");

        assertThat(result.isError()).isFalse();
        assertThat(text(result).lines()).containsExactly(
                "https://hrva.cc/menu -> https://example.com/menu",
                "Status: ACTIVE",
                "Total visits: 42",
                "Recent unique visitors: 5",
                "Recent clicks: 9",
                "Last visit: 2026-10-19T08:30:00Z",
                "Created: 2026-10-01T12:00:00Z",
                RETENTION_SENTENCE);
    }

    @Test
    void statsWithoutVisitsSaysSo() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L)).thenReturn(new UrlAnalyticsDto(33L, "menu", "https://example.com/menu",
                "ACTIVE", 0L, 0L, 0L, null, systemTime("2026-10-01T12:00:00Z"), List.of()));

        final var result = linkTools.linkStats("menu");

        assertThat(text(result).lines()).contains("Last visit: none", RETENTION_SENTENCE);
    }

    private static String text(final CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static Page<UrlResponse> page(final UrlResponse... links) {
        return new PageImpl<>(List.of(links));
    }

    private static UrlResponse link(final String alias, final String destination, final Long visits, final String status) {
        return new UrlResponse(1L, destination, alias, LocalDateTime.now(), null, visits, null, "ACTIVE".equals(status),
                status, Set.of(), false, "user@localhost");
    }

    private static UrlResponse link(final Long id, final String alias, final boolean active) {
        return new UrlResponse(id, "https://example.com/" + alias, alias, LocalDateTime.now(), null, 1L, null, active,
                active ? "ACTIVE" : "EXPIRED", Set.of(), false, "user@localhost");
    }

    private static UrlResponse created(final String alias, final Long visitLimit, final LocalDateTime expirationDate) {
        final var destination = "menu".equals(alias) ? "https://example.com/menu" : "https://example.com/page";
        return new UrlResponse(11L, destination, alias, LocalDateTime.now(), expirationDate, 0L, visitLimit, true, "ACTIVE",
                Set.of("ai"), false, "user@localhost");
    }

    private static UrlAnalyticsDto analytics(final Long id, final String alias) {
        return new UrlAnalyticsDto(id, alias, "https://example.com/" + alias, "ACTIVE", 42L, 5L, 9L,
                systemTime("2026-10-19T08:30:00Z"), systemTime("2026-10-01T12:00:00Z"), List.of());
    }

    private static LocalDateTime systemTime(final String instant) {
        return LocalDateTime.ofInstant(Instant.parse(instant), ZoneId.systemDefault());
    }

}
