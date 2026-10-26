package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.JacksonConfiguration;
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
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import cc.hrva.urlshortener.service.impl.DefaultPublicBioPageService;
import java.time.Clock;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultPublicBioPageServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-26T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final String NOT_AVAILABLE = "This page isn't available.";
    private static final String CAMERA = codePoints(0x1F4F8);

    @Mock
    private BioPageRepository bioPageRepository;

    @Mock
    private BioPageButtonRepository bioPageButtonRepository;

    @Mock
    private LinkReviewRepository linkReviewRepository;

    private final User ana = User.builder().id(7L).email("ana@example.com").active(true).build();
    private BioPage page;
    private PublicBioPageService publicBioPageService;

    @BeforeEach
    void setUp() {
        page = BioPage.builder().id(12L).owner(ana).handle("ana").displayName("Ana Horvat").bio("Photos from Zagreb")
                .avatarKind(BioAvatarKind.EMOJI).avatarEmoji(CAMERA).accent(BioAccent.TEAL).theme(BioTheme.AUTO)
                .createdAt(NOW).updatedAt(NOW).build();
        final var clock = Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
        publicBioPageService = new DefaultPublicBioPageService(bioPageRepository,
                new BioPageResponses(bioPageButtonRepository, linkReviewRepository, clock));
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
