package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.DomainRef;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.LinkSummary;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.exception.InvalidAnalyticsRangeException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CreatedVia;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.validation.Validation;
import java.time.Instant;
import java.time.LocalDate;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private static final String DAILY_UNIQUE_NOTE = "Unique visitors are counted per day, so one person on two different days counts twice. "
            + "Bot clicks aren't in the clicks or visitor counts.";
    private static final Breakdowns NO_BREAKDOWNS = new Breakdowns(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

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
        linkTools = new LinkTools(urlService, appProperties, mcpUserResolver, analyticsService, validator, new ShortLinks(appProperties));
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
    void listLinksPrintsTheDomainLink() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(
                onDomain(44L, "menu", "go.example.com"),
                link("abc12", "https://example.com/first", 3L, "ACTIVE")));

        final var result = linkTools.listLinks(null, null, null);

        assertThat(text(result).lines()).containsExactly(
                "https://go.example.com/menu -> https://example.com/menu | 1 visits | ACTIVE",
                "https://hrva.cc/abc12 -> https://example.com/first | 3 visits | ACTIVE");
    }

    @Test
    void filtersByTag() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link("abc12", "https://example.com/first", 1L, "ACTIVE")));

        linkTools.listLinks("example", "work", null);

        final var search = ArgumentCaptor.forClass(UrlSearchDto.class);
        verify(urlService).getAllMyUrls(isNull(), any(), search.capture());
        assertThat(search.getValue()).isEqualTo(new UrlSearchDto("example", null, null, "work", null, null, null, null));
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
        when(urlService.saveUrlRouting(any(), any(), any())).thenReturn(created("abc12", null, null));

        linkTools.shortenUrl("https://example.com/page", null, null, null, List.of("ai", "work"));
        linkTools.shortenUrl("https://example.com/page", null, null, null, List.of("Work", "AI", "work"));
        linkTools.shortenUrl("https://example.com/page", null, null, null, null);

        final var request = ArgumentCaptor.forClass(CreateUrlDto.class);
        verify(urlService, times(3)).saveUrlRouting(request.capture(), isNull(), any());
        assertThat(request.getAllValues().get(0).getTags()).containsExactly("ai", "work");
        assertThat(request.getAllValues().get(1).getTags()).containsExactly("ai", "work");
        assertThat(request.getAllValues().get(2).getTags()).containsExactly("ai");
    }

    @Test
    void shortenUrlIsCreatedViaMcp() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any(), any())).thenReturn(created("abc12", null, null));

        linkTools.shortenUrl("https://example.com/page", null, null, null, null);

        verify(urlService).saveUrlRouting(any(), isNull(), eq(CreatedVia.MCP));
    }

    @Test
    void shortenKeepsAiTagWhenTheTagLimitIsReached() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any(), any())).thenReturn(created("abc12", null, null));
        final var tenTags = IntStream.rangeClosed(1, 10).mapToObj(index -> "tag" + index).toList();

        linkTools.shortenUrl("https://example.com/page", null, null, null, tenTags);

        final var request = ArgumentCaptor.forClass(CreateUrlDto.class);
        verify(urlService).saveUrlRouting(request.capture(), isNull(), any());
        assertThat(request.getValue().getTags()).hasSize(10).contains("ai");
    }

    @Test
    void shortenPassesAliasLimitAndExpiry() {
        final var expiry = systemTime("2030-06-30T21:59:00Z");
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any(), any())).thenReturn(created("menu", 100L, expiry));

        final var result = linkTools.shortenUrl("https://example.com/menu", "menu", 100L, "2030-06-30T23:59:00+02:00", List.of("food"));

        final var request = ArgumentCaptor.forClass(CreateUrlDto.class);
        final var order = inOrder(mcpUserResolver, urlService);
        order.verify(mcpUserResolver).currentUser();
        order.verify(urlService).saveUrlRouting(request.capture(), isNull(), any());
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
        when(urlService.saveUrlRouting(any(), any(), any())).thenReturn(created("abc12", null, null));

        linkTools.shortenUrl("https://example.com", null, null, "2030-06-30T21:59:00Z", null);
        linkTools.shortenUrl("https://example.com", null, null, " 2030-06-30T23:59:00+02:00 ", null);
        linkTools.shortenUrl("https://example.com", null, null, "2030-06-30T23:59:00+02:00[Europe/Zagreb]", null);

        final var request = ArgumentCaptor.forClass(CreateUrlDto.class);
        verify(urlService, times(3)).saveUrlRouting(request.capture(), isNull(), any());
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
        verify(urlService, never()).saveUrlRouting(any(), any(), any());
    }

    @Test
    void shortenWithoutOptionsReportsOnlyTheLink() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any(), any())).thenReturn(created("abc12", null, null));

        final var result = linkTools.shortenUrl("https://example.com/page", " ", null, "", null);

        final var request = ArgumentCaptor.forClass(CreateUrlDto.class);
        verify(urlService).saveUrlRouting(request.capture(), isNull(), any());
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
        verify(urlService, never()).saveUrlRouting(any(), any(), any());
    }

    @Test
    void shortenRefusesADestinationOver2048CharactersAsAToolError() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);

        final var result = linkTools.shortenUrl("https://example.com/" + "a".repeat(2029), null, null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("Destination URL must be 2048 characters or fewer");
        verify(urlService, never()).saveUrlRouting(any(), any(), any());
    }

    @Test
    void shortenReturnsToolErrorWithBackendMessageWhenAliasTaken() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any(), any())).thenThrow(new ShortUrlAlreadyExistsException("This short URL is already taken. Try another."));

        final var result = linkTools.shortenUrl("https://example.com/menu", "menu", null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("This short URL is already taken. Try another.");
    }

    @Test
    void shortenReturnsToolErrorWithBackendMessageWhenUrlIsUnsafe() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any(), any())).thenThrow(new UrlValidationException("This URL was flagged as unsafe and cannot be shortened"));

        final var result = linkTools.shortenUrl("https://malware.example", null, null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("This URL was flagged as unsafe and cannot be shortened");
    }

    @Test
    void shortenReturnsToolErrorWithTheRefusalWhenTheAccountHitsTheCreateLimit() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), isNull(), any()))
                .thenThrow(new RateLimitExceededException("You've created a lot of links in a short time. Try again in an hour."));

        final var result = linkTools.shortenUrl("https://example.com/menu", null, null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("You've created a lot of links in a short time. Try again in an hour.");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shortenLogsIdsOnly(final CapturedOutput output) {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.saveUrlRouting(any(), any(), any())).thenReturn(new UrlResponse(11L, "https://secret-destination.example/path", "secret-alias",
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
        when(analyticsService.getUrlAnalyticsForOwner(33L, null)).thenReturn(analytics(33L, "menu"));

        final var result = linkTools.linkStats("menu", null);

        assertThat(result.isError()).isFalse();
        verify(analyticsService).getUrlAnalyticsForOwner(33L, null);
        final var pageable = ArgumentCaptor.forClass(Pageable.class);
        final var search = ArgumentCaptor.forClass(UrlSearchDto.class);
        verify(urlService).getAllMyUrls(isNull(), pageable.capture(), search.capture());
        assertThat(pageable.getValue().isUnpaged()).isTrue();
        assertThat(search.getValue()).isEqualTo(new UrlSearchDto("menu", null, null, null, null, null, null, null));
    }

    @Test
    void statsPrefersTheActiveLinkWhenAnAliasWasReused() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(
                link(40L, "menu", false),
                link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L, null)).thenReturn(analytics(33L, "menu"));

        linkTools.linkStats("menu", null);

        verify(analyticsService).getUrlAnalyticsForOwner(33L, null);
    }

    @Test
    void statsAcceptsHostAndUrlForms() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L, null)).thenReturn(analytics(33L, "menu"));

        for (final var form : List.of("menu", "hrva.cc/menu", "https://hrva.cc/menu", " HTTP://HRVA.CC/menu/ ", "/menu")) {
            assertThat(linkTools.linkStats(form, null).isError()).as(form).isFalse();
        }

        final var search = ArgumentCaptor.forClass(UrlSearchDto.class);
        verify(urlService, times(5)).getAllMyUrls(isNull(), any(), search.capture());
        assertThat(search.getAllValues()).extracting(UrlSearchDto::getSearch).containsOnly("menu");
        verify(analyticsService, times(5)).getUrlAnalyticsForOwner(33L, null);
    }

    @Test
    void statsUnknownAliasReturnsToolError() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(31L, "menu-2", true)));

        final var result = linkTools.linkStats("https://hrva.cc/menu", null);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("No link hrva.cc/menu in your account.");
        verifyNoInteractions(analyticsService);
    }

    @Test
    void linkStatsAcceptsTheCallersDomainLink() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(onDomain(44L, "menu", "go.example.com"), link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(44L, null)).thenReturn(analytics(44L, "menu", new DomainRef(7L, "go.example.com")));
        when(analyticsService.getUrlAnalyticsForOwner(33L, null)).thenReturn(analytics(33L, "menu"));

        for (final var form : List.of("go.example.com/menu", "https://go.example.com/menu", " HTTPS://Go.Example.com/menu/ ")) {
            final var result = linkTools.linkStats(form, null);
            assertThat(result.isError()).as(form).isFalse();
            assertThat(text(result).lines().findFirst()).as(form).contains("https://go.example.com/menu -> example.com");
        }
        for (final var form : List.of("menu", "hrva.cc/menu", "https://hrva.cc/menu")) {
            assertThat(text(linkTools.linkStats(form, null)).lines().findFirst()).as(form).contains("https://hrva.cc/menu -> example.com");
        }

        verify(analyticsService, times(3)).getUrlAnalyticsForOwner(44L, null);
        verify(analyticsService, times(3)).getUrlAnalyticsForOwner(33L, null);
    }

    @Test
    void linkStatsRefusesSomeoneElsesDomainLink() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(onDomain(44L, "menu", "go.example.com"), link(33L, "menu", true)));

        final var bare = linkTools.linkStats("go.example.org/menu", null);
        final var withScheme = linkTools.linkStats("https://go.example.org/menu", null);

        assertThat(bare.isError()).isTrue();
        assertThat(text(bare)).isEqualTo("No link go.example.org/menu in your account.");
        assertThat(text(withScheme)).isEqualTo("No link go.example.org/menu in your account.");
        verifyNoInteractions(analyticsService);
    }

    @Test
    void statsWithoutAnAliasAsksForOne() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);

        final var result = linkTools.linkStats(" https://hrva.cc/ ", null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("Give the short link, for example menu, hrva.cc/menu or https://hrva.cc/menu.");
        verifyNoInteractions(urlService, analyticsService);
    }

    @Test
    void statsDescribesTheRangeTopValuesAndTheDailyNote() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L, null)).thenReturn(analytics(33L, "menu"));

        final var result = linkTools.linkStats("menu", null);

        assertThat(result.isError()).isFalse();
        assertThat(text(result).lines()).containsExactly(
                "https://hrva.cc/menu -> example.com",
                "Status: ACTIVE",
                "Range: last 30 days, 2026-09-25 to 2026-10-24",
                "Clicks: 120",
                "Daily unique visitors: 95",
                "Bot clicks: 14",
                "Top referrers: direct 60, t.co 20, other 3",
                "Top countries: HR 50, DE 20",
                "Devices: mobile 80, desktop 38",
                "All-time visits: 412",
                "Last visit: 2026-10-24T07:15:00Z",
                "Created: 2026-10-01T12:00:00Z",
                DAILY_UNIQUE_NOTE);
    }

    @Test
    void statsShowsTheFirstFiveValuesOfEachList() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(33L, "menu", true)));
        final var referrers = IntStream.rangeClosed(1, 7).mapToObj(index -> new BreakdownEntry("r" + index, 10 - index)).toList();
        final var countries = List.of(new BreakdownEntry("HR", 9), new BreakdownEntry("DE", 8), new BreakdownEntry("AT", 7),
                new BreakdownEntry("SI", 6), new BreakdownEntry("RS", 5), new BreakdownEntry("other", 4));
        final var devices = List.of(new BreakdownEntry("mobile", 3), new BreakdownEntry("desktop", 2), new BreakdownEntry("tablet", 1),
                new BreakdownEntry("unknown", 1));
        when(analyticsService.getUrlAnalyticsForOwner(33L, null)).thenReturn(analytics(33L, "menu",
                new Breakdowns(referrers, countries, devices, List.of(new BreakdownEntry("chrome", 7)), List.of(new BreakdownEntry("ios", 7)),
                        List.of())));

        final var lines = text(linkTools.linkStats("menu", null)).lines().toList();

        assertThat(lines).contains(
                "Top referrers: r1 9, r2 8, r3 7, r4 6, r5 5",
                "Top countries: HR 9, DE 8, AT 7, SI 6, RS 5",
                "Devices: mobile 3, desktop 2, tablet 1, unknown 1");
        assertThat(lines).noneMatch(line -> line.contains("chrome") || line.contains("ios"));
    }

    @Test
    void statsPassesTheRequestedDays() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L, 7)).thenReturn(analytics(33L, "menu"));

        final var result = linkTools.linkStats("menu", 7);

        assertThat(result.isError()).isFalse();
        verify(analyticsService).getUrlAnalyticsForOwner(33L, 7);
    }

    @Test
    void statsWithAnUnsupportedRangeReturnsTheMessage() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L, 14)).thenThrow(new InvalidAnalyticsRangeException("days must be 7, 30, 90 or 365"));

        final var result = linkTools.linkStats("menu", 14);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("days must be 7, 30, 90 or 365");
    }

    @Test
    void statsWithoutClicksSaysNone() {
        when(mcpUserResolver.currentUser()).thenReturn(CURRENT_USER);
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenReturn(page(link(33L, "menu", true)));
        when(analyticsService.getUrlAnalyticsForOwner(33L, null)).thenReturn(new UrlAnalyticsDto(
                new LinkSummary(33L, "menu", "example.com", "ACTIVE", false, null, null, 0, "https://example.com/menu", null, null),
                30, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 24), new AnalyticsTotals(0, 0, 0), List.of(), NO_BREAKDOWNS));

        final var result = linkTools.linkStats("menu", null);

        assertThat(result.isError()).isFalse();
        assertThat(text(result).lines()).contains(
                "Clicks: 0",
                "Top referrers: none",
                "Top countries: none",
                "Devices: none",
                "All-time visits: 0",
                "Last visit: none",
                "Created: unknown",
                DAILY_UNIQUE_NOTE);
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

    private static UrlResponse onDomain(final Long id, final String alias, final String hostname) {
        return new UrlResponse(id, "https://example.com/" + alias, alias, LocalDateTime.now(), null, 1L, null, true, "ACTIVE", Set.of(), false,
                "user@localhost", List.of(), null, null, null, null, null, new DomainRef(7L, hostname));
    }

    private static UrlResponse created(final String alias, final Long visitLimit, final LocalDateTime expirationDate) {
        final var destination = "menu".equals(alias) ? "https://example.com/menu" : "https://example.com/page";
        return new UrlResponse(11L, destination, alias, LocalDateTime.now(), expirationDate, 0L, visitLimit, true, "ACTIVE",
                Set.of("ai"), false, "user@localhost");
    }

    private static UrlAnalyticsDto analytics(final Long id, final String alias) {
        return analytics(id, alias, new Breakdowns(
                List.of(new BreakdownEntry("direct", 60), new BreakdownEntry("t.co", 20), new BreakdownEntry("other", 3)),
                List.of(new BreakdownEntry("HR", 50), new BreakdownEntry("DE", 20)),
                List.of(new BreakdownEntry("mobile", 80), new BreakdownEntry("desktop", 38)),
                List.of(new BreakdownEntry("chrome", 70)),
                List.of(new BreakdownEntry("android", 60)),
                List.of(new BreakdownEntry("direct", 90), new BreakdownEntry("qr", 30))));
    }

    private static UrlAnalyticsDto analytics(final Long id, final String alias, final DomainRef domain) {
        final var link = new LinkSummary(id, alias, "example.com", "ACTIVE", false,
                systemTime("2026-10-01T12:00:00Z"), systemTime("2026-10-24T07:15:00Z"), 412, "https://example.com/" + alias, null, domain);
        return new UrlAnalyticsDto(link, 30, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 24), new AnalyticsTotals(120, 95, 14),
                List.of(), NO_BREAKDOWNS);
    }

    private static UrlAnalyticsDto analytics(final Long id, final String alias, final Breakdowns breakdowns) {
        final var link = new LinkSummary(id, alias, "example.com", "ACTIVE", false,
                systemTime("2026-10-01T12:00:00Z"), systemTime("2026-10-24T07:15:00Z"), 412, "https://example.com/" + alias, null, null);
        return new UrlAnalyticsDto(link, 30, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 24), new AnalyticsTotals(120, 95, 14),
                List.of(), breakdowns);
    }

    private static LocalDateTime systemTime(final String instant) {
        return LocalDateTime.ofInstant(Instant.parse(instant), ZoneId.systemDefault());
    }

}
