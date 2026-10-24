package cc.hrva.urlshortener.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.AnalyticsOverviewDto.TopLink;
import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.dto.LinkClicks;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.LinkSummary;
import cc.hrva.urlshortener.exception.InvalidAnalyticsRangeException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.impl.DefaultAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cc.hrva.urlshortener.model.enums.StatDimension.BROWSER;
import static cc.hrva.urlshortener.model.enums.StatDimension.COUNTRY;
import static cc.hrva.urlshortener.model.enums.StatDimension.DEVICE;
import static cc.hrva.urlshortener.model.enums.StatDimension.OS;
import static cc.hrva.urlshortener.model.enums.StatDimension.REFERRER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAnalyticsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final String RANGE_ERROR = "days must be 7, 30, 90 or 365";

    private AnalyticsService analyticsService;

    @Mock
    private UserService userService;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private LinkStatsRepository linkStatsRepository;

    @Mock
    private AnalyticsDays analyticsDays;

    private User owner;

    @BeforeEach
    void setUp() {
        this.analyticsService = new DefaultAnalyticsService(userService, urlRepository, linkStatsRepository, analyticsDays);
        this.owner = User.builder().id(1L).email("owner@example.com")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
    }

    @Test
    void overviewZeroFillsAndTotalsTheRange() {
        givenOwnLinks(List.of(ownLink()));
        when(analyticsDays.today()).thenReturn(TODAY);
        when(linkStatsRepository.findOwnerDaily(1L, LocalDate.of(2026, 10, 18), TODAY)).thenReturn(List.of(
                new AnalyticsDay(LocalDate.of(2026, 10, 19), 4, 3, 1),
                new AnalyticsDay(TODAY, 6, 5, 2)));
        when(linkStatsRepository.findOwnerTopLinks(1L, LocalDate.of(2026, 10, 18), TODAY, 10)).thenReturn(List.of());

        final var overview = analyticsService.getOverview(7);

        assertThat(overview.days()).isEqualTo(7);
        assertThat(overview.from()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(overview.to()).isEqualTo(TODAY);
        assertThat(overview.daily()).containsExactly(
                new AnalyticsDay(LocalDate.of(2026, 10, 18), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 19), 4, 3, 1),
                new AnalyticsDay(LocalDate.of(2026, 10, 20), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 21), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 22), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 23), 0, 0, 0),
                new AnalyticsDay(TODAY, 6, 5, 2));
        assertThat(overview.totals()).isEqualTo(new AnalyticsTotals(10, 8, 3));
        assertThat(overview.topLinks()).isEmpty();
    }

    @Test
    void overviewDefaultRangeIs30Days() {
        givenOwnLinks(List.of(ownLink()));
        givenNoOwnerStats();

        final var overview = analyticsService.getOverview(null);

        assertThat(overview.days()).isEqualTo(30);
        assertThat(overview.from()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(overview.to()).isEqualTo(TODAY);
        assertThat(overview.daily()).hasSize(30);
        assertThat(overview.totals()).isEqualTo(new AnalyticsTotals(0, 0, 0));
        verify(linkStatsRepository).findOwnerDaily(1L, LocalDate.of(2026, 9, 25), TODAY);
        verify(linkStatsRepository).findOwnerTopLinks(1L, LocalDate.of(2026, 9, 25), TODAY, 10);
    }

    @Test
    void overviewTopLinksCarryShortUrlAndHost() {
        final var menu = Url.builder().id(8L).shortUrl("menu").longUrl("https://shop.example.com/a?b=c").visits(200L).active(true)
                .owner(owner).build();
        givenOwnLinks(List.of(ownLink(), menu));
        when(analyticsDays.today()).thenReturn(TODAY);
        when(linkStatsRepository.findOwnerDaily(eq(1L), any(), any())).thenReturn(List.of());
        when(linkStatsRepository.findOwnerTopLinks(1L, LocalDate.of(2026, 9, 25), TODAY, 10)).thenReturn(List.of(
                new LinkClicks(8L, 120, 95),
                new LinkClicks(7L, 4, 3)));

        final var overview = analyticsService.getOverview(30);

        assertThat(overview.topLinks()).containsExactly(
                new TopLink(8L, "menu", "shop.example.com", 120, 95),
                new TopLink(7L, "a", "a.com", 4, 3));
    }

    @Test
    void overviewSkipsATopLinkMissingFromTheOwnersLinks() {
        givenOwnLinks(List.of(ownLink()));
        when(analyticsDays.today()).thenReturn(TODAY);
        when(linkStatsRepository.findOwnerDaily(eq(1L), any(), any())).thenReturn(List.of());
        when(linkStatsRepository.findOwnerTopLinks(eq(1L), any(), any(), eq(10))).thenReturn(List.of(
                new LinkClicks(9L, 5, 4),
                new LinkClicks(7L, 4, 3)));

        final var overview = analyticsService.getOverview(30);

        assertThat(overview.topLinks()).containsExactly(new TopLink(7L, "a", "a.com", 4, 3));
    }

    @Test
    void overviewKeepsTheAllTimeCounters() {
        final var now = LocalDateTime.now();
        final var active = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").visits(10L).active(true)
                .expirationDate(now.plusDays(1)).build();
        final var expired = Url.builder().id(2L).shortUrl("b").longUrl("https://b.com").visits(50L).active(false)
                .expirationDate(now.minusDays(1)).build();
        final var deactivated = Url.builder().id(3L).shortUrl("c").longUrl("https://c.com").active(false).build();
        givenOwnLinks(List.of(active, expired, deactivated));
        givenNoOwnerStats();

        final var overview = analyticsService.getOverview(30);

        assertThat(overview.totalUrls()).isEqualTo(3);
        assertThat(overview.activeUrls()).isEqualTo(1);
        assertThat(overview.expiredUrls()).isEqualTo(1);
        assertThat(overview.totalVisits()).isEqualTo(60);
    }

    @Test
    void overviewRejectsOtherRanges() {
        for (final var days : List.of(0, 14, -7, 31, 366)) {
            assertThatThrownBy(() -> analyticsService.getOverview(days))
                    .as("days %d", days)
                    .isInstanceOf(InvalidAnalyticsRangeException.class)
                    .hasMessage(RANGE_ERROR);
        }

        verifyNoInteractions(userService, urlRepository, linkStatsRepository, analyticsDays);
    }

    @Test
    void perLinkAnalyticsZeroFillsTheRange() {
        givenOwnLink(ownLink());
        when(analyticsDays.today()).thenReturn(TODAY);
        when(linkStatsRepository.findDaily(7L, LocalDate.of(2026, 10, 18), TODAY)).thenReturn(List.of(
                new AnalyticsDay(LocalDate.of(2026, 10, 20), 3, 2, 1),
                new AnalyticsDay(TODAY, 5, 4, 0)));
        when(linkStatsRepository.findBreakdownTotals(7L, LocalDate.of(2026, 10, 18), TODAY)).thenReturn(Map.of());

        final var analytics = analyticsService.getUrlAnalytics(7L, 7);

        assertThat(analytics.days()).isEqualTo(7);
        assertThat(analytics.from()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(analytics.to()).isEqualTo(TODAY);
        assertThat(analytics.daily()).containsExactly(
                new AnalyticsDay(LocalDate.of(2026, 10, 18), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 19), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 20), 3, 2, 1),
                new AnalyticsDay(LocalDate.of(2026, 10, 21), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 22), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 23), 0, 0, 0),
                new AnalyticsDay(TODAY, 5, 4, 0));
        assertThat(analytics.totals()).isEqualTo(new AnalyticsTotals(8, 6, 1));
    }

    @Test
    void rangeAcrossTheDstChangeListsEachDateOnce() {
        givenOwnLink(ownLink());
        givenNoStoredStats(LocalDate.of(2026, 10, 26));

        final var analytics = analyticsService.getUrlAnalytics(7L, 7);

        assertThat(analytics.daily())
                .extracting(AnalyticsDay::date)
                .doesNotHaveDuplicates()
                .containsExactly(
                        LocalDate.of(2026, 10, 20),
                        LocalDate.of(2026, 10, 21),
                        LocalDate.of(2026, 10, 22),
                        LocalDate.of(2026, 10, 23),
                        LocalDate.of(2026, 10, 24),
                        LocalDate.of(2026, 10, 25),
                        LocalDate.of(2026, 10, 26));
        verify(linkStatsRepository).findDaily(7L, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 26));
    }

    @Test
    void defaultRangeIs30Days() {
        givenOwnLink(ownLink());
        givenNoStoredStats(TODAY);

        final var analytics = analyticsService.getUrlAnalytics(7L, null);

        assertThat(analytics.days()).isEqualTo(30);
        assertThat(analytics.from()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(analytics.daily()).hasSize(30);
        assertThat(analytics.daily().getFirst().date()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(analytics.daily().getLast().date()).isEqualTo(TODAY);
        assertThat(analytics.totals()).isEqualTo(new AnalyticsTotals(0, 0, 0));
        verify(linkStatsRepository).findDaily(7L, LocalDate.of(2026, 9, 25), TODAY);
        verify(linkStatsRepository).findBreakdownTotals(7L, LocalDate.of(2026, 9, 25), TODAY);
    }

    @Test
    void everySupportedRangeEndsToday() {
        givenOwnLink(ownLink());
        givenNoStoredStats(TODAY);

        for (final var days : List.of(7, 30, 90, 365)) {
            final var analytics = analyticsService.getUrlAnalytics(7L, days);

            assertThat(analytics.days()).as("days %d", days).isEqualTo(days);
            assertThat(analytics.daily()).as("days %d", days).hasSize(days);
            assertThat(analytics.from()).as("days %d", days).isEqualTo(TODAY.minusDays(days - 1L));
            assertThat(analytics.to()).as("days %d", days).isEqualTo(TODAY);
        }
    }

    @Test
    void rejectsOtherRanges() {
        for (final var days : List.of(14, 0, -7, 31, 366)) {
            assertThatThrownBy(() -> analyticsService.getUrlAnalytics(7L, days))
                    .as("days %d", days)
                    .isInstanceOf(InvalidAnalyticsRangeException.class)
                    .hasMessage(RANGE_ERROR);
            assertThatThrownBy(() -> analyticsService.getUrlAnalyticsForOwner(7L, days))
                    .as("days %d", days)
                    .isInstanceOf(InvalidAnalyticsRangeException.class)
                    .hasMessage(RANGE_ERROR);
        }

        verifyNoInteractions(userService, urlRepository, linkStatsRepository);
    }

    @Test
    void breakdownKeepsTheTop10AndFoldsTheRestIntoOther() {
        givenOwnLink(ownLink());
        when(analyticsDays.today()).thenReturn(TODAY);
        when(linkStatsRepository.findDaily(eq(7L), any(), any())).thenReturn(List.of());
        final var referrers = new ArrayList<BreakdownEntry>();
        IntStream.rangeClosed(1, 7).forEach(index -> referrers.add(new BreakdownEntry("r" + index, 13 - index)));
        referrers.add(new BreakdownEntry("other", 5));
        IntStream.rangeClosed(8, 12).forEach(index -> referrers.add(new BreakdownEntry("r" + index, 13 - index)));
        when(linkStatsRepository.findBreakdownTotals(eq(7L), any(), any())).thenReturn(Map.of(REFERRER, referrers));

        final var analytics = analyticsService.getUrlAnalytics(7L, 30);

        final var expected = new ArrayList<BreakdownEntry>();
        IntStream.rangeClosed(1, 10).forEach(index -> expected.add(new BreakdownEntry("r" + index, 13 - index)));
        expected.add(new BreakdownEntry("other", 8));
        assertThat(analytics.breakdowns().referrers()).containsExactlyElementsOf(expected);
        assertThat(analytics.breakdowns().countries()).isEmpty();
    }

    @Test
    void breakdownWithoutLeftoversHasNoOther() {
        givenOwnLink(ownLink());
        when(analyticsDays.today()).thenReturn(TODAY);
        when(linkStatsRepository.findDaily(eq(7L), any(), any())).thenReturn(List.of());
        when(linkStatsRepository.findBreakdownTotals(eq(7L), any(), any())).thenReturn(Map.of(
                REFERRER, List.of(new BreakdownEntry("direct", 4), new BreakdownEntry("t.co", 1)),
                COUNTRY, List.of(new BreakdownEntry("HR", 3), new BreakdownEntry("DE", 2)),
                DEVICE, List.of(new BreakdownEntry("mobile", 5)),
                BROWSER, List.of(new BreakdownEntry("safari", 3), new BreakdownEntry("chrome", 2)),
                OS, List.of(new BreakdownEntry("ios", 3), new BreakdownEntry("android", 2))));

        final var breakdowns = analyticsService.getUrlAnalytics(7L, 30).breakdowns();

        assertThat(breakdowns.referrers()).containsExactly(new BreakdownEntry("direct", 4), new BreakdownEntry("t.co", 1));
        assertThat(breakdowns.countries()).containsExactly(new BreakdownEntry("HR", 3), new BreakdownEntry("DE", 2));
        assertThat(breakdowns.devices()).containsExactly(new BreakdownEntry("mobile", 5));
        assertThat(breakdowns.browsers()).containsExactly(new BreakdownEntry("safari", 3), new BreakdownEntry("chrome", 2));
        assertThat(breakdowns.operatingSystems()).containsExactly(new BreakdownEntry("ios", 3), new BreakdownEntry("android", 2));
    }

    @Test
    void storedOtherIsShownLastEvenWithoutLeftovers() {
        givenOwnLink(ownLink());
        when(analyticsDays.today()).thenReturn(TODAY);
        when(linkStatsRepository.findDaily(eq(7L), any(), any())).thenReturn(List.of());
        when(linkStatsRepository.findBreakdownTotals(eq(7L), any(), any())).thenReturn(Map.of(
                COUNTRY, List.of(new BreakdownEntry("other", 9), new BreakdownEntry("HR", 3))));

        final var breakdowns = analyticsService.getUrlAnalytics(7L, 30).breakdowns();

        assertThat(breakdowns.countries()).containsExactly(new BreakdownEntry("HR", 3), new BreakdownEntry("other", 9));
    }

    @Test
    void summaryShowsTheHostAndThePasswordFlag() {
        final var created = LocalDateTime.of(2026, 10, 1, 12, 0);
        final var lastAccessed = LocalDateTime.of(2026, 10, 24, 9, 15);
        final var url = Url.builder().id(7L).shortUrl("menu").longUrl("https://shop.example.com/a?b=c").visits(412L).active(true)
                .passwordHash("$2a$10$hash").createDate(created).lastAccessed(lastAccessed).owner(owner).build();
        givenOwnLink(url);
        givenNoStoredStats(TODAY);

        final var analytics = analyticsService.getUrlAnalytics(7L, null);

        assertThat(analytics.link()).isEqualTo(
                new LinkSummary(7L, "menu", "shop.example.com", "ACTIVE", true, created, lastAccessed, 412));
    }

    @Test
    void summaryOfALinkWithoutPasswordOrVisits() {
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("not a url").active(false).owner(owner).build();
        givenOwnLink(url);
        givenNoStoredStats(TODAY);

        final var link = analyticsService.getUrlAnalytics(7L, null).link();

        assertThat(link.destinationHost()).isEmpty();
        assertThat(link.passwordProtected()).isFalse();
        assertThat(link.visits()).isZero();
        assertThat(link.status()).isEqualTo("DEACTIVATED");
    }

    @Test
    void shouldBuildUrlAnalyticsForOwner() {
        givenOwnLink(ownLink());
        givenNoStoredStats(TODAY);

        final var analytics = analyticsService.getUrlAnalytics(7L, null);

        assertThat(analytics.link().id()).isEqualTo(7L);
        assertThat(analytics.link().visits()).isEqualTo(3);
        assertThat(analytics.link().status()).isEqualTo("ACTIVE");
        assertThat(analytics.link().destinationHost()).isEqualTo("a.com");
        assertThat(analytics.daily()).hasSize(30);
    }

    @Test
    void shouldRejectUrlAnalyticsForNonOwner() {
        final var otherOwner = User.builder().id(99L).build();
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").active(true).owner(otherOwner).build();
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> analyticsService.getUrlAnalytics(7L, null))
                .isInstanceOf(NoAuthorizationException.class);
        verifyNoInteractions(linkStatsRepository);
    }

    @Test
    void shouldAllowAdminToViewAnyUrlAnalytics() {
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").visits(1L).active(true)
                .owner(User.builder().id(99L).build()).build();
        when(userService.getUserFromToken()).thenReturn(admin());
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        givenNoStoredStats(TODAY);

        final var analytics = analyticsService.getUrlAnalytics(7L, null);

        assertThat(analytics.link().id()).isEqualTo(7L);
        assertThat(analytics.totals()).isEqualTo(new AnalyticsTotals(0, 0, 0));
    }

    @Test
    void shouldFailUrlAnalyticsWhenUrlMissing() {
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analyticsService.getUrlAnalytics(7L, null))
                .isInstanceOf(UrlNotFoundException.class);
        verifyNoInteractions(linkStatsRepository);
    }

    @Test
    void ownerOnlyStatsRejectAdminReadingForeignUrl() {
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").visits(1L).active(true)
                .owner(User.builder().id(99L).build()).build();
        when(userService.getUserFromToken()).thenReturn(admin());
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> analyticsService.getUrlAnalyticsForOwner(7L, null))
                .isInstanceOf(NoAuthorizationException.class);
        verifyNoInteractions(linkStatsRepository);
    }

    @Test
    void ownerOnlyStatsRejectUrlWithoutOwner() {
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").active(true).build();
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> analyticsService.getUrlAnalyticsForOwner(7L, null))
                .isInstanceOf(NoAuthorizationException.class);
        verifyNoInteractions(linkStatsRepository);
    }

    @Test
    void ownerOnlyStatsBuildAnalyticsForOwner() {
        givenOwnLink(ownLink());
        givenNoStoredStats(TODAY);

        final var analytics = analyticsService.getUrlAnalyticsForOwner(7L, 90);

        assertThat(analytics.link().id()).isEqualTo(7L);
        assertThat(analytics.link().visits()).isEqualTo(3);
        assertThat(analytics.days()).isEqualTo(90);
        assertThat(analytics.daily()).hasSize(90);
        verify(linkStatsRepository).findDaily(7L, TODAY.minusDays(89), TODAY);
    }

    @Test
    void ownerOnlyStatsFailWhenUrlMissing() {
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analyticsService.getUrlAnalyticsForOwner(7L, null))
                .isInstanceOf(UrlNotFoundException.class);
    }

    private Url ownLink() {
        return Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").visits(3L).active(true).owner(owner).build();
    }

    private void givenOwnLink(final Url url) {
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
    }

    private void givenOwnLinks(final List<Url> urls) {
        when(userService.getUserFromToken()).thenReturn(owner);
        when(urlRepository.findByOwner(owner)).thenReturn(urls);
    }

    private void givenNoOwnerStats() {
        when(analyticsDays.today()).thenReturn(TODAY);
        when(linkStatsRepository.findOwnerDaily(eq(1L), any(), any())).thenReturn(List.of());
        when(linkStatsRepository.findOwnerTopLinks(eq(1L), any(), any(), eq(10))).thenReturn(List.of());
    }

    private void givenNoStoredStats(final LocalDate today) {
        when(analyticsDays.today()).thenReturn(today);
        when(linkStatsRepository.findDaily(eq(7L), any(), any())).thenReturn(List.of());
        when(linkStatsRepository.findBreakdownTotals(eq(7L), any(), any())).thenReturn(Map.of());
    }

    private static User admin() {
        return User.builder().id(2L).email("admin@example.com")
                .authorities(Collections.singletonList(Authorities.builder().id(1L).name("ROLE_ADMIN").build()))
                .build();
    }

}
