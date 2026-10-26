package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.bio.BioPageAnalyticsResponse;
import cc.hrva.urlshortener.dto.bio.BioViewDay;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.exception.InvalidAnalyticsRangeException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.BioPageViewStatsRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.analytics.ReferrerClassifier;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import cc.hrva.urlshortener.service.impl.DefaultBioPageAnalyticsService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultBioPageAnalyticsServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-26T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final LocalDate WEEK_START = LocalDate.of(2026, 10, 20);
    private static final String NO_PAGE = "You don't have a bio page yet.";

    @Mock
    private UserService userService;

    @Mock
    private BioPageRepository bioPageRepository;

    @Mock
    private BioPageButtonRepository bioPageButtonRepository;

    @Mock
    private LinkReviewRepository linkReviewRepository;

    @Mock
    private BioPageViewStatsRepository bioPageViewStatsRepository;

    @Mock
    private LinkStatsRepository linkStatsRepository;

    private final User ana = User.builder().id(7L).email("ana@example.com").active(true).build();
    private BioPage page;
    private BioPageAnalyticsService bioPageAnalyticsService;

    @BeforeEach
    void setUp() {
        page = BioPage.builder().id(12L).owner(ana).handle("ana").displayName("Ana").createdAt(NOW).updatedAt(NOW).build();
        final var clock = Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("https://app.hrva.cc");
        final var referrerClassifier = new ReferrerClassifier(appProperties);
        referrerClassifier.init();
        bioPageAnalyticsService = new DefaultBioPageAnalyticsService(userService, bioPageRepository,
                new BioPageResponses(bioPageButtonRepository, linkReviewRepository, clock), bioPageViewStatsRepository, linkStatsRepository,
                referrerClassifier, new AnalyticsDays(clock, appProperties));
    }

    @Test
    void daysAreValidatedBeforeTheCallerIsRead() {
        assertThatThrownBy(() -> bioPageAnalyticsService.analytics(14))
                .isInstanceOf(InvalidAnalyticsRangeException.class)
                .hasMessage("days must be 7, 30, 90 or 365");

        verifyNoInteractions(userService, bioPageRepository, bioPageViewStatsRepository, linkStatsRepository);
    }

    @Test
    void dailyIsZeroFilledToTheRange() {
        hasPage();
        when(bioPageViewStatsRepository.findDaily(12L, WEEK_START, TODAY))
                .thenReturn(List.of(new BioViewDay(WEEK_START.plusDays(1), 3, 1), new BioViewDay(TODAY, 2, 0)));

        final var analytics = bioPageAnalyticsService.analytics(7);

        assertThat(analytics.days()).isEqualTo(7);
        assertThat(analytics.from()).isEqualTo(WEEK_START);
        assertThat(analytics.to()).isEqualTo(TODAY);
        assertThat(analytics.handle()).isEqualTo("ana");
        assertThat(analytics.views()).isEqualTo(5);
        assertThat(analytics.botViews()).isEqualTo(1);
        assertThat(analytics.daily()).containsExactly(
                new BioViewDay(WEEK_START, 0, 0),
                new BioViewDay(WEEK_START.plusDays(1), 3, 1),
                new BioViewDay(WEEK_START.plusDays(2), 0, 0),
                new BioViewDay(WEEK_START.plusDays(3), 0, 0),
                new BioViewDay(WEEK_START.plusDays(4), 0, 0),
                new BioViewDay(WEEK_START.plusDays(5), 0, 0),
                new BioViewDay(TODAY, 2, 0));
        assertThat(analytics.buttons()).isEmpty();
    }

    @Test
    void theRangeIsThirtyDaysByDefault() {
        hasPage();

        final var analytics = bioPageAnalyticsService.analytics(null);

        assertThat(analytics.days()).isEqualTo(30);
        assertThat(analytics.from()).isEqualTo(TODAY.minusDays(29));
        assertThat(analytics.daily()).hasSize(30).allMatch(day -> day.views() == 0 && day.botViews() == 0);
        verify(bioPageViewStatsRepository).findDaily(12L, TODAY.minusDays(29), TODAY);
    }

    @Test
    void buttonsCarryBioAndAllClicks() {
        hasPage();
        final var instagram = link(41L, "ana-ig");
        final var blog = link(42L, "ana-blog");
        onPage(button(31L, instagram, "Instagram", 0, true), button(32L, blog, "Blog", 1, true));
        when(linkStatsRepository.sumReferrerClicksByUrl(List.of(41L, 42L), "hrva.cc", WEEK_START, TODAY)).thenReturn(Map.of(41L, 9L));
        when(linkStatsRepository.sumClicksByUrl(List.of(41L, 42L), WEEK_START, TODAY)).thenReturn(Map.of(41L, 14L, 42L, 3L));

        final var analytics = bioPageAnalyticsService.analytics(7);

        assertThat(analytics.buttons())
                .extracting(BioPageAnalyticsResponse.ButtonClicks::buttonId, BioPageAnalyticsResponse.ButtonClicks::urlId,
                        BioPageAnalyticsResponse.ButtonClicks::shortUrl, BioPageAnalyticsResponse.ButtonClicks::title,
                        BioPageAnalyticsResponse.ButtonClicks::shownOnPage, BioPageAnalyticsResponse.ButtonClicks::fromHrvaPages,
                        BioPageAnalyticsResponse.ButtonClicks::allClicks)
                .containsExactly(
                        tuple(31L, 41L, "ana-ig", "Instagram", true, 9L, 14L),
                        tuple(32L, 42L, "ana-blog", "Blog", true, 0L, 3L));
    }

    @Test
    void aHiddenButtonIsListedButNotShown() {
        hasPage();
        final var blocked = link(42L, "ana-promo");
        blocked.setBlockReason(BlockReason.BLOCKLIST);
        final var inReview = link(43L, "ana-shop");
        onPage(button(31L, link(41L, "ana-drafts"), "Drafts", 0, false), button(32L, blocked, "Promo", 1, true),
                button(33L, inReview, "Shop", 2, true), button(34L, link(44L, "ana-blog"), "Blog", 3, true));
        when(linkReviewRepository.findOpenSystemFlaggedUrlIds(anyCollection())).thenReturn(List.of(43L));

        final var analytics = bioPageAnalyticsService.analytics(7);

        assertThat(analytics.buttons())
                .extracting(BioPageAnalyticsResponse.ButtonClicks::title, BioPageAnalyticsResponse.ButtonClicks::shownOnPage)
                .containsExactly(tuple("Drafts", false), tuple("Promo", false), tuple("Shop", false), tuple("Blog", true));
    }

    @Test
    void aPageWithoutButtonsLooksUpNoReviews() {
        hasPage();

        assertThat(bioPageAnalyticsService.analytics(7).buttons()).isEmpty();

        verifyNoInteractions(linkReviewRepository);
    }

    @Test
    void aPageWithoutAHandleStillHasItsFigures() {
        page.setHandle(null);
        page.setHandleRevokedReason("Impersonation");
        hasPage();

        assertThat(bioPageAnalyticsService.analytics(7).handle()).isNull();
    }

    @Test
    void noPageIs404() {
        when(userService.getUserFromToken()).thenReturn(ana);
        when(bioPageRepository.findByOwner(ana)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bioPageAnalyticsService.analytics(7))
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NO_PAGE);

        verifyNoInteractions(bioPageViewStatsRepository, linkStatsRepository);
    }

    private void hasPage() {
        when(userService.getUserFromToken()).thenReturn(ana);
        when(bioPageRepository.findByOwner(ana)).thenReturn(Optional.of(page));
    }

    private void onPage(final BioPageButton... buttons) {
        when(bioPageButtonRepository.findByPageWithUrl(page)).thenReturn(List.of(buttons));
    }

    private BioPageButton button(final long id, final Url url, final String title, final int sortOrder, final boolean visible) {
        return BioPageButton.builder().id(id).page(page).url(url).title(title).sortOrder(sortOrder).visible(visible).build();
    }

    private Url link(final long id, final String alias) {
        return Url.builder().id(id).shortUrl(alias).longUrl("https://" + alias + ".example.com/x").owner(ana).active(true).visits(0L).build();
    }

}
