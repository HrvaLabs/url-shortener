package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.dto.bio.PublicBioPageResponse;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioIcon;
import cc.hrva.urlshortener.model.enums.BioTheme;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageViewStatsRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.analytics.UniqueVisitorCounter;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import cc.hrva.urlshortener.service.impl.DefaultPublicBioPageService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultPublicBioPageServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-26T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final String NOT_AVAILABLE = "This page isn't available.";
    private static final String CAMERA = codePoints(0x1F4F8);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final String VISITOR_IP = "203.0.113.7";
    private static final ClickRequest HUMAN = ClickRequest.of(VISITOR_IP,
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
            "https://app.hrva.cc/bio/ana", "HR");
    private static final ClickRequest BOT = ClickRequest.of(VISITOR_IP, "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            null, null);
    private static final String VIEW_KEY = RateLimiter.clientKey("bio-view", VISITOR_IP);
    private static final Duration VIEW_WINDOW = Duration.ofMinutes(1);

    @Mock
    private BioPageRepository bioPageRepository;

    @Mock
    private BioPageButtonRepository bioPageButtonRepository;

    @Mock
    private LinkReviewRepository linkReviewRepository;

    @Mock
    private BioPageViewStatsRepository bioPageViewStatsRepository;

    @Mock
    private UniqueVisitorCounter uniqueVisitorCounter;

    @Mock
    private RateLimiter rateLimiter;

    private final User ana = User.builder().id(7L).email("ana@example.com").active(true).build();
    private BioPage page;
    private PublicBioPageService publicBioPageService;

    @BeforeEach
    void setUp() {
        page = BioPage.builder().id(12L).owner(ana).handle("ana").displayName("Ana Horvat").bio("Photos from Zagreb")
                .avatarKind(BioAvatarKind.EMOJI).avatarEmoji(CAMERA).accent(BioAccent.TEAL).theme(BioTheme.AUTO)
                .createdAt(NOW).updatedAt(NOW).build();
        publicBioPageService = serviceAt(NOW_INSTANT);
    }

    @Test
    void aPublicPageShowsOnlyLiveVisibleButtonsInOrder() {
        isPublished();
        final var instagram = button(31L, link(41L, "ana-ig"), "Instagram", 0, true);
        instagram.setIcon(BioIcon.INSTAGRAM);
        final var drafts = button(32L, link(42L, "ana-drafts"), "Drafts", 1, false);
        final var blog = button(33L, link(43L, "ana-blog"), "Blog", 2, true);
        onPage(instagram, drafts, blog);

        final var response = publicBioPageService.find("ana");

        assertThat(response.handle()).isEqualTo("ana");
        assertThat(response.displayName()).isEqualTo("Ana Horvat");
        assertThat(response.bio()).isEqualTo("Photos from Zagreb");
        assertThat(response.avatarKind()).isEqualTo(BioAvatarKind.EMOJI);
        assertThat(response.avatarEmoji()).isEqualTo(CAMERA);
        assertThat(response.accent()).isEqualTo(BioAccent.TEAL);
        assertThat(response.theme()).isEqualTo(BioTheme.AUTO);
        assertThat(response.indexable()).isFalse();
        assertThat(response.buttons())
                .extracting(PublicBioPageResponse.Button::title, PublicBioPageResponse.Button::icon, PublicBioPageResponse.Button::shortUrl)
                .containsExactly(tuple("Instagram", BioIcon.INSTAGRAM, "ana-ig"), tuple("Blog", null, "ana-blog"));
    }

    @Test
    void aButtonDisappearsWhenItsLinkStopsWorking() {
        isPublished();
        final var blocked = link(41L, "blocked");
        blocked.setBlockReason(BlockReason.BLOCKLIST);
        final var deactivated = link(42L, "deactivated");
        deactivated.setActive(false);
        final var expired = link(43L, "expired");
        expired.setExpirationDate(NOW.minusSeconds(1));
        final var atItsLimit = link(44L, "at-its-limit");
        atItsLimit.setVisitLimit(10L);
        atItsLimit.setVisits(10L);
        final var inReview = link(45L, "in-review");
        final var live = link(46L, "live");
        onPage(button(31L, blocked, "Blocked", 0, true), button(32L, deactivated, "Deactivated", 1, true),
                button(33L, expired, "Expired", 2, true), button(34L, atItsLimit, "At its limit", 3, true),
                button(35L, inReview, "In review", 4, true), button(36L, live, "Live", 5, true));
        when(linkReviewRepository.findOpenSystemFlaggedUrlIds(anyCollection())).thenReturn(List.of(45L));

        assertThat(publicBioPageService.find("ana").buttons())
                .extracting(PublicBioPageResponse.Button::title)
                .containsExactly("Live");
    }

    @Test
    void aButtonWaitingForReviewIsNotShown() {
        isPublished();
        onPage(button(31L, link(41L, "ana-ig"), "Instagram", 0, true));
        when(linkReviewRepository.findOpenSystemFlaggedUrlIds(List.of(41L))).thenReturn(List.of(41L));

        assertThat(publicBioPageService.find("ana").buttons()).isEmpty();
    }

    @Test
    void aVisitorReportAloneHidesNothing() {
        isPublished();
        onPage(button(31L, link(41L, "ana-ig"), "Instagram", 0, true));
        when(linkReviewRepository.findOpenSystemFlaggedUrlIds(List.of(41L))).thenReturn(List.of());

        assertThat(publicBioPageService.find("ana").buttons())
                .extracting(PublicBioPageResponse.Button::shortUrl)
                .containsExactly("ana-ig");
    }

    @Test
    void onlyVisibleButtonsAreLookedUpForReviews() {
        isPublished();
        onPage(button(31L, link(41L, "ana-ig"), "Instagram", 0, false), button(32L, link(42L, "ana-blog"), "Blog", 1, true));

        publicBioPageService.find("ana");

        verify(linkReviewRepository).findOpenSystemFlaggedUrlIds(List.of(42L));
    }

    @Test
    void aPageWithoutVisibleButtonsLooksUpNoReviews() {
        isPublished();
        onPage(button(31L, link(41L, "ana-ig"), "Instagram", 0, false));

        assertThat(publicBioPageService.find("ana").buttons()).isEmpty();

        verifyNoInteractions(linkReviewRepository);
    }

    @Test
    void aHiddenPageIsNotAvailable() {
        page.setHiddenAt(NOW);
        page.setHiddenReason("Spam");
        isPublished();

        assertNotAvailable("ana");
    }

    @Test
    void isPublicRefusesAPageWithoutAHandle() {
        page.setHandle(null);
        page.setHandleRevokedReason("Impersonation");
        isPublished();

        assertNotAvailable("ana");
    }

    @Test
    void aDeactivatedOwnersPageIsNotAvailable() {
        ana.setActive(false);
        isPublished();

        assertNotAvailable("ana");
    }

    @Test
    void anUnknownHandleIsNotAvailable() {
        when(bioPageRepository.findByHandleWithOwner("nobody")).thenReturn(Optional.empty());

        assertNotAvailable("nobody");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "ana!", "ana.ig", "---", "ana/blog", "a234567890123456789012345678901"})
    void aMalformedHandleIsNotAvailableWithoutAQuery(final String handle) {
        assertNotAvailable(handle);

        verifyNoInteractions(bioPageRepository, bioPageButtonRepository, linkReviewRepository);
    }

    @Test
    void aMissingHandleIsNotAvailableWithoutAQuery() {
        assertNotAvailable(null);

        verifyNoInteractions(bioPageRepository);
    }

    @Test
    void theHandleIsMatchedCaseInsensitively() {
        isPublished();

        assertThat(publicBioPageService.find("Ana").handle()).isEqualTo("ana");
        assertThat(publicBioPageService.find(" @ANA ").handle()).isEqualTo("ana");

        verify(bioPageRepository, times(2)).findByHandleWithOwner("ana");
    }

    @Test
    void theResponseCarriesNoIdsOrDestinations() {
        isPublished();
        final var instagram = button(31L, link(41L, "ana-ig"), "Instagram", 0, true);
        instagram.setIcon(BioIcon.INSTAGRAM);
        onPage(instagram);
        final var jsonMapper = appJsonMapper();

        final var json = jsonMapper.readTree(jsonMapper.writeValueAsString(publicBioPageService.find("ana")));

        assertThat(fieldNamesOf(json)).doesNotContain("id", "urlId", "owner", "destinationHost", "longUrl", "linkState",
                "hidden", "hiddenReason", "handleRevokedReason", "visible", "createdAt", "updatedAt");
        assertThat(json).isEqualTo(jsonMapper.readTree("""
                {"handle":"ana","displayName":"Ana Horvat","bio":"Photos from Zagreb","avatarKind":"EMOJI","avatarEmoji":"%s",\
                "accent":"TEAL","theme":"AUTO","indexable":false,"buttons":[{"title":"Instagram","icon":"INSTAGRAM","shortUrl":"ana-ig"}]}"""
                .formatted(CAMERA)));
    }

    @Test
    void aHumanViewCountsOnceADay() {
        isPublished();
        allowsViews();
        when(uniqueVisitorCounter.isNewBioPageVisitor(12L, TODAY, HUMAN)).thenReturn(true, false);

        publicBioPageService.recordView("ana", HUMAN);
        publicBioPageService.recordView("ana", HUMAN);

        verify(bioPageViewStatsRepository).increment(12L, TODAY, 1, 0);
        verifyNoMoreInteractions(bioPageViewStatsRepository);
        verify(rateLimiter, times(2)).tryAcquire(VIEW_KEY, 60, VIEW_WINDOW);
    }

    @Test
    void aBotViewIsCountedApartEveryTime() {
        isPublished();
        allowsViews();

        publicBioPageService.recordView("ana", BOT);
        publicBioPageService.recordView("ana", BOT);
        publicBioPageService.recordView("ana", ClickRequest.of(VISITOR_IP, null, null, null));

        verify(bioPageViewStatsRepository, times(3)).increment(12L, TODAY, 0, 1);
        verifyNoMoreInteractions(bioPageViewStatsRepository);
        verifyNoInteractions(uniqueVisitorCounter);
    }

    @Test
    void aViewOfAHiddenPageCountsNothing() {
        page.setHiddenAt(NOW);
        isPublished();

        publicBioPageService.recordView("ana", HUMAN);

        verifyNoInteractions(rateLimiter, uniqueVisitorCounter, bioPageViewStatsRepository);
    }

    @Test
    void aViewOfAPageThatIsNotPublicCountsNothing() {
        ana.setActive(false);
        isPublished();
        when(bioPageRepository.findByHandleWithOwner("nobody")).thenReturn(Optional.empty());

        publicBioPageService.recordView("ana", HUMAN);
        publicBioPageService.recordView("nobody", HUMAN);
        publicBioPageService.recordView("ana/blog", HUMAN);
        publicBioPageService.recordView(null, HUMAN);

        verifyNoInteractions(rateLimiter, uniqueVisitorCounter, bioPageViewStatsRepository);
        verify(bioPageRepository).findByHandleWithOwner("ana");
        verify(bioPageRepository).findByHandleWithOwner("nobody");
        verifyNoMoreInteractions(bioPageRepository);
    }

    @Test
    void theHandleOfAViewIsMatchedLikeTheRead() {
        isPublished();
        allowsViews();
        when(uniqueVisitorCounter.isNewBioPageVisitor(12L, TODAY, HUMAN)).thenReturn(true);

        publicBioPageService.recordView(" @Ana ", HUMAN);

        verify(bioPageViewStatsRepository).increment(12L, TODAY, 1, 0);
    }

    @Test
    void aViewOverTheNetworkLimitCountsNothing() {
        isPublished();
        when(rateLimiter.tryAcquire(VIEW_KEY, 60, VIEW_WINDOW)).thenReturn(false);

        publicBioPageService.recordView("ana", HUMAN);
        publicBioPageService.recordView("ana", BOT);

        verifyNoInteractions(uniqueVisitorCounter, bioPageViewStatsRepository);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void redisDownStillCounts(final CapturedOutput output) {
        isPublished();
        when(rateLimiter.tryAcquire(VIEW_KEY, 60, VIEW_WINDOW))
                .thenThrow(new RateLimiterUnavailableException(new RedisConnectionFailureException("Unable to connect to Redis")));
        when(uniqueVisitorCounter.isNewBioPageVisitor(12L, TODAY, HUMAN)).thenReturn(true);

        publicBioPageService.recordView("ana", HUMAN);

        verify(bioPageViewStatsRepository).increment(12L, TODAY, 1, 0);
        assertThat(output)
                .contains("Bio page view rate limit unavailable, counting the view pageId=12")
                .doesNotContain(VISITOR_IP);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aDatabaseErrorNeverEscapes(final CapturedOutput output) {
        isPublished();
        allowsViews();
        when(uniqueVisitorCounter.isNewBioPageVisitor(12L, TODAY, HUMAN)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("Connection refused")).when(bioPageViewStatsRepository).increment(12L, TODAY, 1, 0);

        assertThatCode(() -> publicBioPageService.recordView("ana", HUMAN)).doesNotThrowAnyException();

        assertThat(output)
                .contains("Bio page view dropped pageId=12 exception=" + DataAccessResourceFailureException.class.getName())
                .doesNotContain(VISITOR_IP)
                .doesNotContain("ana@example.com")
                .doesNotContain("Connection refused");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailedLookupNeverEscapes(final CapturedOutput output) {
        when(bioPageRepository.findByHandleWithOwner("ana")).thenThrow(new DataAccessResourceFailureException("Connection refused"));

        assertThatCode(() -> publicBioPageService.recordView("ana", HUMAN)).doesNotThrowAnyException();

        assertThat(output)
                .contains("Bio page view dropped pageId=unknown exception=" + DataAccessResourceFailureException.class.getName())
                .doesNotContain(VISITOR_IP);
        verifyNoInteractions(rateLimiter, uniqueVisitorCounter, bioPageViewStatsRepository);
    }

    @Test
    void aViewIsCountedOnTheAnalyticsDay() {
        final var lateEvening = serviceAt(Instant.parse("2026-10-26T23:30:00Z"));
        isPublished();
        allowsViews();
        when(uniqueVisitorCounter.isNewBioPageVisitor(12L, TODAY.plusDays(1), HUMAN)).thenReturn(true);

        lateEvening.recordView("ana", HUMAN);

        verify(bioPageViewStatsRepository).increment(12L, TODAY.plusDays(1), 1, 0);
    }

    private PublicBioPageService serviceAt(final Instant now) {
        final var clock = Clock.fixed(now, ZoneOffset.UTC);
        return new DefaultPublicBioPageService(bioPageRepository, new BioPageResponses(bioPageButtonRepository, linkReviewRepository, clock),
                bioPageViewStatsRepository, uniqueVisitorCounter, rateLimiter, new AnalyticsDays(clock, new AppProperties()));
    }

    private void allowsViews() {
        when(rateLimiter.tryAcquire(VIEW_KEY, 60, VIEW_WINDOW)).thenReturn(true);
    }

    private void assertNotAvailable(final String handle) {
        assertThatThrownBy(() -> publicBioPageService.find(handle))
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NOT_AVAILABLE);
    }

    private void isPublished() {
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));
    }

    private void onPage(final BioPageButton... buttons) {
        when(bioPageButtonRepository.findByPageWithUrl(page)).thenReturn(List.of(buttons));
    }

    private BioPageButton button(final long id, final Url url, final String title, final int sortOrder, final boolean visible) {
        return BioPageButton.builder().id(id).page(page).url(url).title(title).sortOrder(sortOrder).visible(visible).build();
    }

    private Url link(final long id, final String alias) {
        return Url.builder().id(id).shortUrl(alias).longUrl("https://" + alias + ".example.com/secret?token=x").owner(ana).active(true)
                .visits(0L).build();
    }

    private static JsonMapper appJsonMapper() {
        final var jacksonConfiguration = new JacksonConfiguration();
        final var builder = JsonMapper.builder();
        jacksonConfiguration.throwableWithoutStackTrace().customize(builder);
        jacksonConfiguration.enumsByNameOnly().customize(builder);
        return builder.build();
    }

    private static List<String> fieldNamesOf(final JsonNode node) {
        final var names = new ArrayList<String>();
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                names.add(entry.getKey());
                names.addAll(fieldNamesOf(entry.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(element -> names.addAll(fieldNamesOf(element)));
        }
        return names;
    }

    private static String codePoints(final int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

}
