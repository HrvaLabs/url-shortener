package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.bio.BioHandleAvailabilityResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioPageButtonDto;
import cc.hrva.urlshortener.dto.bio.BioPageCreateDto;
import cc.hrva.urlshortener.dto.bio.BioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioPageUpdateDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.BioHandleUnavailableException;
import cc.hrva.urlshortener.exception.BioPageConflictException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.anon.RateLimiterUnavailableException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioIcon;
import cc.hrva.urlshortener.model.enums.BioLinkState;
import cc.hrva.urlshortener.model.enums.BioTheme;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.bio.BioHandles;
import cc.hrva.urlshortener.service.bio.BioPageLock;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import cc.hrva.urlshortener.service.impl.DefaultBioPageService;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultBioPageServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-26T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 20, 8, 0);
    private static final String SAVE_KEY = "bio-save:user:7";
    private static final String HANDLE_KEY = "bio-handle:user:7";
    private static final String CHECK_KEY = "bio-handle-check:user:7";
    private static final Duration SAVE_WINDOW = Duration.ofHours(1);
    private static final Duration HANDLE_WINDOW = Duration.ofHours(24);
    private static final Duration CHECK_WINDOW = Duration.ofMinutes(10);
    private static final String NO_PAGE = "You don't have a bio page yet.";
    private static final String PAGE_CHANGED = "This page changed. Reload and try again.";
    private static final String ALREADY_HAS_PAGE = "You already have a bio page.";
    private static final String TAKEN = "This handle is taken. Try another.";
    private static final String RESERVED = "This handle is reserved. Try another.";
    private static final String LENGTH = "A handle must be 3 to 30 characters.";
    private static final String NOT_YOUR_LINK = "You can only add your own short links.";
    private static final String DUPLICATE_LINK = "Each link can be on your page only once.";
    private static final String TITLE_REQUIRED = "Give the button a title";
    private static final String EMOJI_REQUIRED = "Pick one emoji for the avatar.";
    private static final String SAVES_REFUSED = "You've saved your bio page a lot in a short time. Try again in a few minutes.";
    private static final String HANDLE_CHANGES_REFUSED = "You've changed your handle a lot today. Try again tomorrow.";
    private static final String CHECKS_REFUSED = "Too many handle checks. Try again in a few minutes.";
    private static final String RIGHT_TO_LEFT_OVERRIDE = codePoints(0x202E);
    private static final String BELL = codePoints(0x0007);
    private static final String CAMERA = codePoints(0x1F4F8);
    private static final String DEVANAGARI_QA = codePoints(0x0958);
    private static final String ZERO_WIDTH_SPACE = codePoints(0x200B);
    private static final String ZERO_WIDTH_JOINER = codePoints(0x200D);
    private static final String ZERO_WIDTH_NON_JOINER = codePoints(0x200C);
    private static final String INHIBIT_SYMMETRIC_SWAPPING = codePoints(0x206A);
    private static final String HANGUL_FILLER = codePoints(0x3164);
    private static final String HIDDEN_PAGE_NOT_DELETABLE =
            "An admin hid this page, so it can't be deleted here. You can still delete your whole account in Settings.";

    @Mock
    private UserService userService;

    @Mock
    private BioPageRepository bioPageRepository;

    @Mock
    private BioPageButtonRepository bioPageButtonRepository;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private LinkReviewRepository linkReviewRepository;

    @Mock
    private BioHandles bioHandles;

    @Mock
    private BioPageLock bioPageLock;

    @Mock
    private RateLimiter rateLimiter;

    @Captor
    private ArgumentCaptor<List<BioPageButton>> savedButtons;

    private final User ana = User.builder().id(7L).email("ana@example.com").active(true).build();
    private final User bob = User.builder().id(8L).email("bob@example.com").active(true).build();
    private BioPage page;
    private BioPageService bioPageService;

    @BeforeEach
    void setUp() {
        page = BioPage.builder().id(12L).owner(ana).handle("ana").displayName("Ana").bio("")
                .avatarKind(BioAvatarKind.INITIALS).accent(BioAccent.BLUE).theme(BioTheme.AUTO)
                .createdAt(CREATED_AT).updatedAt(CREATED_AT).build();
        lenient().when(userService.getUserFromToken()).thenReturn(ana);
        lenient().when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        lenient().when(bioPageLock.lock(any(BioPage.class))).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        final var clock = Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
        bioPageService = new DefaultBioPageService(userService, bioPageRepository, bioPageLock, bioPageButtonRepository, urlRepository,
                bioHandles, new BioPageResponses(bioPageButtonRepository, linkReviewRepository, clock), rateLimiter, clock);
    }

    @Test
    void mineShowsTheEditorView() {
        hasPage();
        page.setHiddenAt(NOW);
        page.setHiddenReason("Spam");
        final var instagram = link(41L, "ana-ig", ana);
        instagram.setLongUrl("https://www.instagram.com/ana?igsh=1");
        final var blog = link(42L, "ana-blog", ana);
        final var instagramButton = button(31L, instagram, 0);
        instagramButton.setIcon(BioIcon.INSTAGRAM);
        final var blogButton = button(32L, blog, 1);
        blogButton.setVisible(false);
        onPage(instagramButton, blogButton);

        final var response = bioPageService.mine();

        assertThat(response.id()).isEqualTo(12L);
        assertThat(response.handle()).isEqualTo("ana");
        assertThat(response.hidden()).isTrue();
        assertThat(response.hiddenReason()).isEqualTo("Spam");
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
        assertThat(response.buttons())
                .extracting(BioPageResponse.Button::id, BioPageResponse.Button::urlId, BioPageResponse.Button::shortUrl,
                        BioPageResponse.Button::destinationHost, BioPageResponse.Button::icon, BioPageResponse.Button::visible,
                        BioPageResponse.Button::linkState, BioPageResponse.Button::shownOnPage)
                .containsExactly(
                        tuple(31L, 41L, "ana-ig", "www.instagram.com", BioIcon.INSTAGRAM, true, BioLinkState.LIVE, true),
                        tuple(32L, 42L, "ana-blog", "ana-blog.example.com", null, false, BioLinkState.LIVE, false));
        verify(linkReviewRepository).findOpenSystemFlaggedUrlIds(List.of(41L, 42L));
    }

    @Test
    void aPageWithoutButtonsLooksUpNoReviews() {
        hasPage();

        assertThat(bioPageService.mine().buttons()).isEmpty();

        verifyNoInteractions(linkReviewRepository);
    }

    @Test
    void withoutAPageTheOwnerApiIs404() {
        when(bioPageRepository.findByOwner(ana)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bioPageService.mine()).isInstanceOf(BioPageNotFoundException.class).hasMessage(NO_PAGE);
        assertThatThrownBy(() -> bioPageService.update(update())).isInstanceOf(BioPageNotFoundException.class).hasMessage(NO_PAGE);
        assertThatThrownBy(() -> bioPageService.changeHandle(new BioHandleDto("bob"))).isInstanceOf(BioPageNotFoundException.class).hasMessage(NO_PAGE);
        assertThatThrownBy(() -> bioPageService.delete()).isInstanceOf(BioPageNotFoundException.class).hasMessage(NO_PAGE);
    }

    @Test
    void createsWithTheNormalizedHandle() {
        when(bioPageRepository.findByOwner(ana)).thenReturn(Optional.empty());
        savesReturnTheirArgument();

        final var response = bioPageService.create(new BioPageCreateDto(" @Ana "));

        final var saved = ArgumentCaptor.forClass(BioPage.class);
        final var inOrder = inOrder(rateLimiter, bioHandles, bioPageRepository);
        inOrder.verify(rateLimiter).tryAcquire(HANDLE_KEY, 5, HANDLE_WINDOW);
        inOrder.verify(bioHandles).requireAvailable("ana", null);
        inOrder.verify(bioPageRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getHandle()).isEqualTo("ana");
        assertThat(saved.getValue().getOwner()).isSameAs(ana);
        assertThat(response.handle()).isEqualTo("ana");
        assertThat(response.buttons()).isEmpty();
    }

    @Test
    void aSecondPageIsRefused() {
        hasPage();

        assertThatThrownBy(() -> bioPageService.create(new BioPageCreateDto("bob")))
                .isInstanceOf(BioHandleUnavailableException.class)
                .hasMessage(ALREADY_HAS_PAGE);

        verifyNoInteractions(rateLimiter, bioHandles);
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void aMalformedHandleIsRefusedBeforeTheRateLimit() {
        when(bioPageRepository.findByOwner(ana)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bioPageService.create(new BioPageCreateDto("a")))
                .isInstanceOf(ApiException.class)
                .hasMessage(LENGTH);

        verifyNoInteractions(rateLimiter, bioHandles);
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void aDoubleCreateIs409() {
        when(bioPageRepository.findByOwner(ana)).thenReturn(Optional.empty(), Optional.of(page));
        when(bioPageRepository.saveAndFlush(any(BioPage.class))).thenThrow(new DataIntegrityViolationException("uq_bio_page_user_id"));

        assertThatThrownBy(() -> bioPageService.create(new BioPageCreateDto("ana")))
                .isInstanceOf(BioHandleUnavailableException.class)
                .hasMessage(ALREADY_HAS_PAGE);
    }

    @Test
    void aLostHandleRaceIs409() {
        when(bioPageRepository.findByOwner(ana)).thenReturn(Optional.empty());
        when(bioPageRepository.saveAndFlush(any(BioPage.class))).thenThrow(new DataIntegrityViolationException("uq_bio_page_handle"));

        assertThatThrownBy(() -> bioPageService.create(new BioPageCreateDto("ana")))
                .isInstanceOf(BioHandleUnavailableException.class)
                .hasMessage(TAKEN);
    }

    @Test
    void createOverTheLimitIs429() {
        when(bioPageRepository.findByOwner(ana)).thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(HANDLE_KEY, 5, HANDLE_WINDOW)).thenReturn(false);

        assertThatThrownBy(() -> bioPageService.create(new BioPageCreateDto("ana")))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(HANDLE_CHANGES_REFUSED);

        verifyNoInteractions(bioHandles);
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void savingStoresTheProfileAndTouchesUpdatedAt() {
        hasPage();
        savesReturnTheirArgument();
        final var dto = update();
        dto.setIndexable(true);

        final var response = bioPageService.update(dto);

        verify(rateLimiter).tryAcquire(SAVE_KEY, 60, SAVE_WINDOW);
        verify(bioPageRepository).saveAndFlush(page);
        assertThat(page.getDisplayName()).isEqualTo("Ana Horvat");
        assertThat(page.getBio()).isEqualTo("Photos from Zagreb");
        assertThat(page.getAccent()).isEqualTo(BioAccent.TEAL);
        assertThat(page.getTheme()).isEqualTo(BioTheme.DARK);
        assertThat(page.isIndexable()).isTrue();
        assertThat(page.getUpdatedAt()).isEqualTo(NOW);
        assertThat(response.displayName()).isEqualTo("Ana Horvat");
        assertThat(response.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void leftOutSwitchesMeanVisibleAndNotIndexable() {
        hasPage();
        savesReturnTheirArgument();
        page.setIndexable(true);
        ownsLinks(link(41L, "ana-ig", ana));
        final var dto = update(buttonDto(41L, "Instagram"));
        dto.setIndexable(null);

        final var response = bioPageService.update(dto);

        assertThat(page.isIndexable()).isFalse();
        assertThat(response.indexable()).isFalse();
        verify(bioPageButtonRepository).saveAll(savedButtons.capture());
        assertThat(savedButtons.getValue()).extracting(BioPageButton::isVisible).containsExactly(true);
    }

    @Test
    void anotherUsersLinkIsRefused() {
        hasPage();
        ownsLinks(link(41L, "bob-ig", bob));

        assertRefusedBeforeWriting(update(buttonDto(41L, "Instagram")), NOT_YOUR_LINK);
    }

    @Test
    void anAnonymousLinkIsRefused() {
        hasPage();
        ownsLinks(link(41L, "anon", null));

        assertRefusedBeforeWriting(update(buttonDto(41L, "Instagram")), NOT_YOUR_LINK);
    }

    @Test
    void aMissingLinkIsRefused() {
        hasPage();
        ownsLinks();

        assertRefusedBeforeWriting(update(buttonDto(41L, "Instagram")), NOT_YOUR_LINK);
    }

    @Test
    void theSameLinkTwiceIsRefused() {
        hasPage();

        assertRefusedBeforeWriting(update(buttonDto(41L, "Instagram"), buttonDto(41L, "Instagram again")), DUPLICATE_LINK);

        verifyNoInteractions(urlRepository);
    }

    @Test
    void aNewlyAddedBlockedLinkIsRefusedWithItsAlias() {
        hasPage();
        final var promo = link(43L, "promo", ana);
        promo.setBlockReason(BlockReason.ADMIN);
        ownsLinks(promo);

        assertRefusedBeforeWriting(update(buttonDto(43L, "Promo")), "Only working links can be added. /promo isn't working right now.");
    }

    @Test
    void aNewlyAddedLinkPastItsLimitIsRefused() {
        hasPage();
        final var full = link(43L, "full", ana);
        full.setVisitLimit(5L);
        full.setVisits(5L);
        ownsLinks(full);

        assertRefusedBeforeWriting(update(buttonDto(43L, "Full")), "Only working links can be added. /full isn't working right now.");
    }

    @Test
    void anExistingButtonWhoseLinkDiedStillSaves() {
        hasPage();
        savesReturnTheirArgument();
        final var shop = link(41L, "old-shop", ana);
        shop.setActive(false);
        onPage(button(31L, shop, 0));
        ownsLinks(shop);

        final var response = bioPageService.update(update(buttonDto(41L, "Shop")));

        assertThat(response.buttons())
                .extracting(BioPageResponse.Button::id, BioPageResponse.Button::title, BioPageResponse.Button::linkState, BioPageResponse.Button::shownOnPage)
                .containsExactly(tuple(31L, "Shop", BioLinkState.DEACTIVATED, false));
    }

    @Test
    void aNewLinkInReviewCanBeAdded() {
        hasPage();
        savesReturnTheirArgument();
        final var shop = link(42L, "new-shop", ana);
        ownsLinks(shop);
        when(linkReviewRepository.findOpenSystemFlaggedUrlIds(anyCollection())).thenReturn(List.of(42L));

        final var response = bioPageService.update(update(buttonDto(42L, "Shop")));

        verify(bioPageButtonRepository).saveAll(savedButtons.capture());
        assertThat(savedButtons.getValue()).singleElement()
                .satisfies(button -> assertThat(button.getUrl()).isSameAs(shop))
                .satisfies(button -> assertThat(button.getPage()).isSameAs(page));
        assertThat(response.buttons())
                .extracting(BioPageResponse.Button::linkState, BioPageResponse.Button::shownOnPage)
                .containsExactly(tuple(BioLinkState.IN_REVIEW, false));
    }

    @Test
    void buttonsKeepTheirIdsAndTakeTheRequestOrder() {
        hasPage();
        savesReturnTheirArgument();
        final var instagram = link(41L, "ana-ig", ana);
        final var blog = link(42L, "ana-blog", ana);
        final var instagramButton = button(31L, instagram, 0);
        final var blogButton = button(32L, blog, 1);
        onPage(instagramButton, blogButton);
        ownsLinks(instagram, blog);
        final var hiddenInstagram = buttonDto(41L, "Instagram");
        hiddenInstagram.setVisible(false);
        hiddenInstagram.setIcon(BioIcon.INSTAGRAM);

        final var response = bioPageService.update(update(buttonDto(42L, "Blog"), hiddenInstagram));

        assertThat(response.buttons())
                .extracting(BioPageResponse.Button::id, BioPageResponse.Button::urlId, BioPageResponse.Button::title,
                        BioPageResponse.Button::icon, BioPageResponse.Button::visible)
                .containsExactly(tuple(32L, 42L, "Blog", null, true), tuple(31L, 41L, "Instagram", BioIcon.INSTAGRAM, false));
        assertThat(blogButton.getSortOrder()).isZero();
        assertThat(instagramButton.getSortOrder()).isEqualTo(1);
        verify(bioPageButtonRepository, never()).deleteAllInBatch(anyIterable());
        verify(bioPageButtonRepository, never()).saveAll(anyIterable());
    }

    @Test
    void removedButtonsAreDeletedBeforeNewOnesAreInserted() {
        hasPage();
        savesReturnTheirArgument();
        final var instagram = link(41L, "ana-ig", ana);
        final var blog = link(42L, "ana-blog", ana);
        final var shop = link(43L, "ana-shop", ana);
        final var instagramButton = button(31L, instagram, 0);
        final var blogButton = button(32L, blog, 1);
        onPage(instagramButton, blogButton);
        ownsLinks(instagram, shop);

        final var response = bioPageService.update(update(buttonDto(41L, "Instagram"), buttonDto(43L, "Shop")));

        final var inOrder = inOrder(bioPageButtonRepository, bioPageRepository);
        inOrder.verify(bioPageButtonRepository).deleteAllInBatch(List.of(blogButton));
        inOrder.verify(bioPageButtonRepository).flush();
        inOrder.verify(bioPageButtonRepository).saveAll(savedButtons.capture());
        inOrder.verify(bioPageRepository).saveAndFlush(page);
        assertThat(savedButtons.getValue()).singleElement()
                .satisfies(button -> assertThat(button.getUrl()).isSameAs(shop))
                .satisfies(button -> assertThat(button.getSortOrder()).isEqualTo(1))
                .satisfies(button -> assertThat(button.getTitle()).isEqualTo("Shop"))
                .satisfies(button -> assertThat(button.isVisible()).isTrue());
        assertThat(response.buttons()).extracting(BioPageResponse.Button::urlId).containsExactly(41L, 43L);
    }

    @Test
    void removingEveryButtonLooksUpNoLinks() {
        hasPage();
        savesReturnTheirArgument();
        final var blogButton = button(32L, link(42L, "ana-blog", ana), 0);
        onPage(blogButton);

        final var response = bioPageService.update(update());

        verify(bioPageButtonRepository).deleteAllInBatch(List.of(blogButton));
        verify(bioPageButtonRepository, never()).saveAll(anyIterable());
        verifyNoInteractions(urlRepository, linkReviewRepository);
        assertThat(response.buttons()).isEmpty();
    }

    @Test
    void aSaveThatLostTheRaceForANewLinkIs409() {
        hasPage();
        ownsLinks(link(41L, "ana-ig", ana));
        when(bioPageButtonRepository.saveAll(anyIterable())).thenThrow(violation("uq_bio_page_button_page_url"));

        assertThatThrownBy(() -> bioPageService.update(update(buttonDto(41L, "Instagram"))))
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(PAGE_CHANGED);
    }

    @Test
    void aSaveThatLostTheRaceAtTheFlushIs409() {
        hasPage();
        ownsLinks(link(41L, "ana-ig", ana));
        when(bioPageRepository.saveAndFlush(page)).thenThrow(violation("UQ_BIO_PAGE_BUTTON_PAGE_URL_INDEX_C"));

        assertThatThrownBy(() -> bioPageService.update(update(buttonDto(41L, "Instagram"))))
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(PAGE_CHANGED);
    }

    @Test
    void aLinkDeletedWhileTheSaveAddsItIs409() {
        hasPage();
        ownsLinks(link(41L, "ana-ig", ana));
        when(bioPageButtonRepository.saveAll(anyIterable())).thenThrow(violation("fk_bio_page_button_url"));

        assertThatThrownBy(() -> bioPageService.update(update(buttonDto(41L, "Instagram"))))
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(PAGE_CHANGED);
    }

    @Test
    void aButtonWhoseLinkWasDeletedWhileSavingIs409() {
        hasPage();
        final var instagram = link(41L, "ana-ig", ana);
        ownsLinks(instagram);
        onPage(button(31L, instagram, 0));
        when(bioPageRepository.saveAndFlush(page)).thenThrow(new ObjectOptimisticLockingFailureException(BioPageButton.class, 31L));

        assertThatThrownBy(() -> bioPageService.update(update(buttonDto(41L, "Instagram"))))
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(PAGE_CHANGED);
    }

    @Test
    void anotherIntegrityErrorWhileSavingIsNotHidden() {
        hasPage();
        ownsLinks(link(41L, "ana-ig", ana));
        final var other = violation("fk_bio_page_button_page");
        when(bioPageButtonRepository.saveAll(anyIterable())).thenThrow(other);

        assertThatThrownBy(() -> bioPageService.update(update(buttonDto(41L, "Instagram"))))
                .isSameAs(other);
    }

    @Test
    void aSaveLocksThePageBeforeReadingItsButtons() {
        hasPage();
        savesReturnTheirArgument();

        bioPageService.update(update());

        final var inOrder = inOrder(bioPageRepository, bioPageLock, bioPageButtonRepository);
        inOrder.verify(bioPageRepository).findByOwner(ana);
        inOrder.verify(bioPageLock).lock(page);
        inOrder.verify(bioPageButtonRepository).findByPageWithUrl(page);
        inOrder.verify(bioPageRepository).saveAndFlush(page);
    }

    @Test
    void aSaveWritesThePageAsReadUnderTheLock() {
        hasPage();
        savesReturnTheirArgument();
        final var lockedPage = BioPage.builder().id(12L).owner(ana).handle("ana").displayName("Ana").bio("")
                .avatarKind(BioAvatarKind.INITIALS).accent(BioAccent.BLUE).theme(BioTheme.AUTO)
                .hiddenAt(NOW).hiddenReason("Spam").hiddenBy("admin@localhost")
                .createdAt(CREATED_AT).updatedAt(CREATED_AT).build();
        when(bioPageLock.lock(page)).thenReturn(Optional.of(lockedPage));

        final var response = bioPageService.update(update());

        verify(bioPageRepository).saveAndFlush(lockedPage);
        verify(bioPageRepository, never()).saveAndFlush(page);
        verify(bioPageButtonRepository).findByPageWithUrl(lockedPage);
        assertThat(lockedPage.getDisplayName()).isEqualTo("Ana Horvat");
        assertThat(lockedPage.getHiddenAt()).isEqualTo(NOW);
        assertThat(response.hidden()).isTrue();
    }

    @Test
    void aSaveOfAPageDeletedBeforeItWasLockedIs404() {
        hasPage();
        when(bioPageLock.lock(page)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bioPageService.update(update(buttonDto(41L, "Instagram"))))
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NO_PAGE);

        verifyNoInteractions(urlRepository, bioPageButtonRepository);
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void readingThePageTakesNoLock() {
        hasPage();

        bioPageService.mine();
        bioPageService.availability("bob");

        verifyNoInteractions(bioPageLock);
    }

    @Test
    void anIntegrityErrorWithoutAConstraintNameIsNotHidden() {
        hasPage();
        ownsLinks(link(41L, "ana-ig", ana));
        final var unnamed = new DataIntegrityViolationException("uq_bio_page_button_page_url");
        when(bioPageButtonRepository.saveAll(anyIterable())).thenThrow(unnamed);

        assertThatThrownBy(() -> bioPageService.update(update(buttonDto(41L, "Instagram"))))
                .isSameAs(unnamed);
    }

    @Test
    void initialsClearTheEmoji() {
        hasPage();
        savesReturnTheirArgument();
        page.setAvatarKind(BioAvatarKind.EMOJI);
        page.setAvatarEmoji(CAMERA);
        final var dto = update();
        dto.setAvatarEmoji(CAMERA);

        final var response = bioPageService.update(dto);

        assertThat(page.getAvatarKind()).isEqualTo(BioAvatarKind.INITIALS);
        assertThat(page.getAvatarEmoji()).isNull();
        assertThat(response.avatarEmoji()).isNull();
    }

    @Test
    void anEmojiAvatarIsStoredCleaned() {
        hasPage();
        savesReturnTheirArgument();
        final var dto = update();
        dto.setAvatarKind(BioAvatarKind.EMOJI);
        dto.setAvatarEmoji(" " + CAMERA + RIGHT_TO_LEFT_OVERRIDE + " ");

        bioPageService.update(dto);

        assertThat(page.getAvatarKind()).isEqualTo(BioAvatarKind.EMOJI);
        assertThat(page.getAvatarEmoji()).isEqualTo(CAMERA);
    }

    @Test
    void aBadEmojiIsRefused() {
        hasPage();

        for (final var emoji : Arrays.asList(null, "", "ab", "1", CAMERA + CAMERA)) {
            final var dto = update();
            dto.setAvatarKind(BioAvatarKind.EMOJI);
            dto.setAvatarEmoji(emoji);

            assertRefusedBeforeWriting(dto, EMOJI_REQUIRED);
        }
    }

    @Test
    void textIsCleanedBeforeSaving() {
        hasPage();
        savesReturnTheirArgument();
        ownsLinks(link(41L, "ana-ig", ana));
        final var dto = update(buttonDto(41L, " Insta" + RIGHT_TO_LEFT_OVERRIDE + "gram\n"));
        dto.setDisplayName("  Ana " + RIGHT_TO_LEFT_OVERRIDE + " Horvat  ");
        dto.setBio("Photos\n\nfrom   Zagreb" + BELL);

        bioPageService.update(dto);

        assertThat(page.getDisplayName()).isEqualTo("Ana Horvat");
        assertThat(page.getBio()).isEqualTo("Photos from Zagreb");
        verify(bioPageButtonRepository).saveAll(savedButtons.capture());
        assertThat(savedButtons.getValue()).extracting(BioPageButton::getTitle).containsExactly("Instagram");
    }

    @Test
    void aTitleOfOnlySpacesIsRefused() {
        hasPage();

        assertRefusedBeforeWriting(update(buttonDto(41L, "   ")), TITLE_REQUIRED);
        assertRefusedBeforeWriting(update(buttonDto(41L, " " + RIGHT_TO_LEFT_OVERRIDE + " ")), TITLE_REQUIRED);
    }

    @Test
    void aTitleOfOnlyZeroWidthSpacesIsRefused() {
        hasPage();

        assertRefusedBeforeWriting(update(buttonDto(41L, ZERO_WIDTH_SPACE)), TITLE_REQUIRED);
        assertRefusedBeforeWriting(update(buttonDto(41L, ZERO_WIDTH_SPACE.repeat(10))), TITLE_REQUIRED);
    }

    @Test
    void aTitleOfOnlyInvisibleFormatCharactersIsRefused() {
        hasPage();

        assertRefusedBeforeWriting(update(buttonDto(41L, INHIBIT_SYMMETRIC_SWAPPING)), TITLE_REQUIRED);
        assertRefusedBeforeWriting(update(buttonDto(41L, INHIBIT_SYMMETRIC_SWAPPING.repeat(10))), TITLE_REQUIRED);
        assertRefusedBeforeWriting(update(buttonDto(41L, HANGUL_FILLER)), TITLE_REQUIRED);
        assertRefusedBeforeWriting(update(buttonDto(41L, " " + HANGUL_FILLER.repeat(3) + " ")), TITLE_REQUIRED);
    }

    @Test
    void aDisplayNameOfOnlyZeroWidthSpacesIsStoredEmpty() {
        hasPage();
        savesReturnTheirArgument();
        final var dto = update();
        dto.setDisplayName(ZERO_WIDTH_SPACE.repeat(3));

        final var response = bioPageService.update(dto);

        assertThat(page.getDisplayName()).isEmpty();
        assertThat(response.displayName()).isEmpty();
    }

    @Test
    void anAvatarEmojiEndingWithAJoinerIsRefused() {
        hasPage();
        final var dto = update();
        dto.setAvatarKind(BioAvatarKind.EMOJI);
        dto.setAvatarEmoji(CAMERA + ZERO_WIDTH_JOINER);

        assertRefusedBeforeWriting(dto, EMOJI_REQUIRED);
    }

    @Test
    void anAvatarEmojiStartingOrEndingWithANonJoinerIsRefused() {
        hasPage();

        for (final var emoji : List.of(CAMERA + ZERO_WIDTH_NON_JOINER, ZERO_WIDTH_NON_JOINER + CAMERA)) {
            final var dto = update();
            dto.setAvatarKind(BioAvatarKind.EMOJI);
            dto.setAvatarEmoji(emoji);

            assertRefusedBeforeWriting(dto, EMOJI_REQUIRED);
        }
    }

    @Test
    void textThatGrowsWhenNormalizedIsStillLimited() {
        hasPage();
        final var longName = update();
        longName.setDisplayName(DEVANAGARI_QA.repeat(50));
        final var longBio = update();
        longBio.setBio(DEVANAGARI_QA.repeat(160));

        assertRefusedBeforeWriting(longName, "Name must be 50 characters or fewer");
        assertRefusedBeforeWriting(longBio, "Bio must be 160 characters or fewer");
        assertRefusedBeforeWriting(update(buttonDto(41L, DEVANAGARI_QA.repeat(60))), "Button title must be 60 characters or fewer");
    }

    @Test
    void saveOverTheLimitIs429() {
        when(rateLimiter.tryAcquire(SAVE_KEY, 60, SAVE_WINDOW)).thenReturn(false);

        assertThatThrownBy(() -> bioPageService.update(update()))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(SAVES_REFUSED);

        verifyNoInteractions(bioPageRepository, bioPageButtonRepository, urlRepository);
    }

    @Test
    void redisDownLetsTheSaveThrough() {
        hasPage();
        savesReturnTheirArgument();
        when(rateLimiter.tryAcquire(SAVE_KEY, 60, SAVE_WINDOW)).thenThrow(new RateLimiterUnavailableException(new IllegalStateException("down")));

        bioPageService.update(update());

        verify(bioPageRepository).saveAndFlush(page);
    }

    @Test
    void redisDownLetsHandleChangesAndChecksThrough() {
        hasPage();
        savesReturnTheirArgument();
        final var unavailable = new RateLimiterUnavailableException(new IllegalStateException("down"));
        when(rateLimiter.tryAcquire(HANDLE_KEY, 5, HANDLE_WINDOW)).thenThrow(unavailable);
        when(rateLimiter.tryAcquire(CHECK_KEY, 120, CHECK_WINDOW)).thenThrow(unavailable);
        when(bioHandles.unavailableReason("carl", page)).thenReturn(Optional.empty());

        assertThat(bioPageService.availability("carl").available()).isTrue();
        assertThat(bioPageService.changeHandle(new BioHandleDto("bob")).handle()).isEqualTo("bob");
    }

    @Test
    void renamingHoldsTheOldHandle() {
        hasPage();
        savesReturnTheirArgument();

        final var response = bioPageService.changeHandle(new BioHandleDto(" @Bob "));

        final var inOrder = inOrder(rateLimiter, bioHandles, bioPageRepository);
        inOrder.verify(rateLimiter).tryAcquire(HANDLE_KEY, 5, HANDLE_WINDOW);
        inOrder.verify(bioHandles).requireAvailable("bob", page);
        inOrder.verify(bioHandles).releaseOwnHold("bob", page);
        inOrder.verify(bioHandles).hold("ana", page);
        inOrder.verify(bioPageRepository).saveAndFlush(page);
        assertThat(page.getHandle()).isEqualTo("bob");
        assertThat(response.handle()).isEqualTo("bob");
    }

    @Test
    void aNewHandleAfterARevokeClearsTheReasonAndHoldsNothing() {
        hasPage();
        savesReturnTheirArgument();
        page.setHandle(null);
        page.setHandleRevokedReason("Impersonation");

        final var response = bioPageService.changeHandle(new BioHandleDto("ana-horvat"));

        verify(bioHandles, never()).hold(anyString(), any());
        assertThat(page.getHandle()).isEqualTo("ana-horvat");
        assertThat(page.getHandleRevokedReason()).isNull();
        assertThat(response.handleRevokedReason()).isNull();
    }

    @Test
    void takingBackTheOwnOldHandleReleasesItsHold() {
        page.setHandle("bob");
        hasPage();
        savesReturnTheirArgument();

        bioPageService.changeHandle(new BioHandleDto("ana"));

        final var inOrder = inOrder(bioHandles, bioPageRepository);
        inOrder.verify(bioHandles).requireAvailable("ana", page);
        inOrder.verify(bioHandles).releaseOwnHold("ana", page);
        inOrder.verify(bioHandles).hold("bob", page);
        inOrder.verify(bioPageRepository).saveAndFlush(page);
        assertThat(page.getHandle()).isEqualTo("ana");
    }

    @Test
    void theSameHandleIsANoOp() {
        hasPage();

        final var response = bioPageService.changeHandle(new BioHandleDto(" @ANA "));

        assertThat(response.handle()).isEqualTo("ana");
        verifyNoInteractions(rateLimiter, bioHandles);
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void aHandleChangeLocksThePageBeforeReadingIt() {
        hasPage();
        savesReturnTheirArgument();

        bioPageService.changeHandle(new BioHandleDto("bob"));

        final var inOrder = inOrder(bioPageRepository, bioPageLock, rateLimiter, bioHandles);
        inOrder.verify(bioPageRepository).findByOwner(ana);
        inOrder.verify(bioPageLock).lock(page);
        inOrder.verify(rateLimiter).tryAcquire(HANDLE_KEY, 5, HANDLE_WINDOW);
        inOrder.verify(bioHandles).requireAvailable("bob", page);
        inOrder.verify(bioHandles).hold("ana", page);
        inOrder.verify(bioPageRepository).saveAndFlush(page);
    }

    @Test
    void aHandleChangeWritesThePageAsReadUnderTheLock() {
        hasPage();
        savesReturnTheirArgument();
        final var lockedPage = BioPage.builder().id(12L).owner(ana).handle("ana").displayName("Ana Horvat").bio("Photos from Zagreb")
                .avatarKind(BioAvatarKind.INITIALS).accent(BioAccent.TEAL).theme(BioTheme.DARK)
                .createdAt(CREATED_AT).updatedAt(CREATED_AT).build();
        when(bioPageLock.lock(page)).thenReturn(Optional.of(lockedPage));

        final var response = bioPageService.changeHandle(new BioHandleDto("bob"));

        verify(bioPageRepository).saveAndFlush(lockedPage);
        verify(bioPageRepository, never()).saveAndFlush(page);
        verify(bioHandles).requireAvailable("bob", lockedPage);
        verify(bioHandles).hold("ana", lockedPage);
        assertThat(lockedPage.getHandle()).isEqualTo("bob");
        assertThat(lockedPage.getDisplayName()).isEqualTo("Ana Horvat");
        assertThat(page.getHandle()).isEqualTo("ana");
        assertThat(response.displayName()).isEqualTo("Ana Horvat");
        assertThat(response.accent()).isEqualTo(BioAccent.TEAL);
    }

    @Test
    void aHandleChangeComparesWithTheHandleReadUnderTheLock() {
        hasPage();
        final var lockedPage = BioPage.builder().id(12L).owner(ana).handle("bob").displayName("Ana").bio("")
                .avatarKind(BioAvatarKind.INITIALS).accent(BioAccent.BLUE).theme(BioTheme.AUTO)
                .createdAt(CREATED_AT).updatedAt(CREATED_AT).build();
        when(bioPageLock.lock(page)).thenReturn(Optional.of(lockedPage));

        final var response = bioPageService.changeHandle(new BioHandleDto("Bob"));

        assertThat(response.handle()).isEqualTo("bob");
        verifyNoInteractions(rateLimiter, bioHandles);
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void aHandleChangeOfAPageDeletedBeforeItWasLockedIs404() {
        hasPage();
        when(bioPageLock.lock(page)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bioPageService.changeHandle(new BioHandleDto("bob")))
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NO_PAGE);

        verifyNoInteractions(rateLimiter, bioHandles);
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void aMalformedNewHandleIsRefusedBeforeTheRateLimit() {
        hasPage();

        assertThatThrownBy(() -> bioPageService.changeHandle(new BioHandleDto("a")))
                .isInstanceOf(ApiException.class)
                .hasMessage(LENGTH);

        verifyNoInteractions(rateLimiter, bioHandles);
        assertThat(page.getHandle()).isEqualTo("ana");
    }

    @Test
    void aLostRenameRaceIs409() {
        hasPage();
        when(bioPageRepository.saveAndFlush(page)).thenThrow(new DataIntegrityViolationException("uq_bio_page_handle"));

        assertThatThrownBy(() -> bioPageService.changeHandle(new BioHandleDto("bob")))
                .isInstanceOf(BioHandleUnavailableException.class)
                .hasMessage(TAKEN);
    }

    @Test
    void handleChangeOverTheLimitIs429() {
        hasPage();
        when(rateLimiter.tryAcquire(HANDLE_KEY, 5, HANDLE_WINDOW)).thenReturn(false);

        assertThatThrownBy(() -> bioPageService.changeHandle(new BioHandleDto("bob")))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(HANDLE_CHANGES_REFUSED);

        verifyNoInteractions(bioHandles);
        verify(bioPageRepository, never()).saveAndFlush(any());
        assertThat(page.getHandle()).isEqualTo("ana");
    }

    @Test
    void deletingHoldsTheHandleUnlinked() {
        hasPage();

        bioPageService.delete();

        final var inOrder = inOrder(bioHandles, bioPageRepository);
        inOrder.verify(bioHandles).hold("ana", null);
        inOrder.verify(bioPageRepository).delete(page);
        inOrder.verify(bioPageRepository).flush();
    }

    @Test
    void aDeleteLocksThePageFirst() {
        hasPage();

        bioPageService.delete();

        final var inOrder = inOrder(bioPageRepository, bioPageLock, bioHandles);
        inOrder.verify(bioPageRepository).findByOwner(ana);
        inOrder.verify(bioPageLock).lock(page);
        inOrder.verify(bioHandles).hold("ana", null);
        inOrder.verify(bioPageRepository).delete(page);
    }

    @Test
    void aPageAnAdminHidBeforeTheLockCantBeDeletedByItsOwner() {
        hasPage();
        final var lockedPage = BioPage.builder().id(12L).owner(ana).handle("ana").hiddenAt(NOW).hiddenReason("Spam").build();
        when(bioPageLock.lock(page)).thenReturn(Optional.of(lockedPage));

        assertThatThrownBy(() -> bioPageService.delete())
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(HIDDEN_PAGE_NOT_DELETABLE);

        verifyNoInteractions(bioHandles);
        verify(bioPageRepository, never()).delete(any(BioPage.class));
    }

    @Test
    void aDeleteOfAPageDeletedBeforeItWasLockedIs404() {
        hasPage();
        when(bioPageLock.lock(page)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bioPageService.delete())
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NO_PAGE);

        verifyNoInteractions(bioHandles);
        verify(bioPageRepository, never()).delete(any(BioPage.class));
        verify(bioPageRepository, never()).flush();
    }

    @Test
    void deletingAPageWithoutAHandleHoldsNothing() {
        page.setHandle(null);
        hasPage();

        bioPageService.delete();

        verifyNoInteractions(bioHandles);
        verify(bioPageRepository).delete(page);
    }

    @Test
    void aPageAnAdminHidCantBeDeletedByItsOwner() {
        page.setHiddenAt(NOW);
        page.setHiddenReason("Spam");
        page.setHiddenBy("admin@localhost");
        hasPage();

        assertThatThrownBy(() -> bioPageService.delete())
                .isInstanceOf(BioPageConflictException.class)
                .hasMessage(HIDDEN_PAGE_NOT_DELETABLE);

        verifyNoInteractions(bioHandles);
        verify(bioPageRepository, never()).delete(any(BioPage.class));
        verify(bioPageRepository, never()).flush();
    }

    @Test
    void aPageTheAdminUnhidCanBeDeleted() {
        page.setHiddenReason("Spam");
        page.setHiddenBy("admin@localhost");
        hasPage();

        bioPageService.delete();

        verify(bioHandles).hold("ana", null);
        verify(bioPageRepository).delete(page);
    }

    @Test
    void aDeleteOfAPageAlreadyDeletedMeanwhileIs404() {
        hasPage();
        doThrow(new ObjectOptimisticLockingFailureException(BioPage.class, 12L)).when(bioPageRepository).flush();

        assertThatThrownBy(() -> bioPageService.delete())
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NO_PAGE);
    }

    @Test
    void aDeleteWhoseHandleTheOtherDeleteHeldIs404() {
        hasPage();
        doThrow(violation("pk_bio_handle_hold")).when(bioPageRepository).flush();

        assertThatThrownBy(() -> bioPageService.delete())
                .isInstanceOf(BioPageNotFoundException.class)
                .hasMessage(NO_PAGE);
    }

    @Test
    void anotherIntegrityErrorWhileDeletingIsNotHidden() {
        hasPage();
        final var other = violation("fk_bio_handle_hold_page");
        doThrow(other).when(bioPageRepository).flush();

        assertThatThrownBy(() -> bioPageService.delete())
                .isSameAs(other);
    }

    @Test
    void availabilityReportsFormatAndAvailability() {
        hasPage();
        when(bioHandles.unavailableReason("bob", page)).thenReturn(Optional.empty());
        when(bioHandles.unavailableReason("admin", page)).thenReturn(Optional.of(RESERVED));

        assertThat(bioPageService.availability(" @Bob ")).isEqualTo(new BioHandleAvailabilityResponse("bob", true, null));
        assertThat(bioPageService.availability("Admin")).isEqualTo(new BioHandleAvailabilityResponse("admin", false, RESERVED));
        assertThat(bioPageService.availability("a")).isEqualTo(new BioHandleAvailabilityResponse("a", false, LENGTH));

        verify(rateLimiter, times(3)).tryAcquire(CHECK_KEY, 120, CHECK_WINDOW);
        verify(bioHandles, never()).unavailableReason(eq("a"), any());
    }

    @Test
    void availabilityWithoutAPageChecksForEveryPage() {
        when(bioPageRepository.findByOwner(ana)).thenReturn(Optional.empty());
        when(bioHandles.unavailableReason("ana", null)).thenReturn(Optional.of(TAKEN));

        assertThat(bioPageService.availability("ana")).isEqualTo(new BioHandleAvailabilityResponse("ana", false, TAKEN));
    }

    @Test
    void availabilityOverTheLimitIs429() {
        when(rateLimiter.tryAcquire(CHECK_KEY, 120, CHECK_WINDOW)).thenReturn(false);

        assertThatThrownBy(() -> bioPageService.availability("bob"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(CHECKS_REFUSED);

        verifyNoInteractions(bioHandles, bioPageRepository);
    }

    private void assertRefusedBeforeWriting(final BioPageUpdateDto dto, final String message) {
        assertThatThrownBy(() -> bioPageService.update(dto))
                .isInstanceOf(ApiException.class)
                .hasMessage(message);

        verify(bioPageButtonRepository, never()).deleteAllInBatch(anyIterable());
        verify(bioPageButtonRepository, never()).saveAll(anyIterable());
        verify(bioPageRepository, never()).saveAndFlush(any());
        assertThat(page.getDisplayName()).isEqualTo("Ana");
        assertThat(page.getAccent()).isEqualTo(BioAccent.BLUE);
    }

    private void hasPage() {
        when(bioPageRepository.findByOwner(ana)).thenReturn(Optional.of(page));
    }

    private void onPage(final BioPageButton... buttons) {
        when(bioPageButtonRepository.findByPageWithUrl(page)).thenReturn(List.of(buttons));
    }

    private void ownsLinks(final Url... urls) {
        when(urlRepository.findAllById(anyIterable())).thenReturn(List.of(urls));
    }

    private void savesReturnTheirArgument() {
        when(bioPageRepository.saveAndFlush(any(BioPage.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private BioPageButton button(final long id, final Url url, final int sortOrder) {
        return BioPageButton.builder().id(id).page(page).url(url).title(url.getShortUrl()).sortOrder(sortOrder).visible(true).build();
    }

    private static Url link(final long id, final String alias, final User owner) {
        return Url.builder().id(id).shortUrl(alias).longUrl("https://" + alias + ".example.com/x").owner(owner).active(true).visits(0L).build();
    }

    private static BioPageButtonDto buttonDto(final Long urlId, final String title) {
        return BioPageButtonDto.builder().urlId(urlId).title(title).build();
    }

    private static BioPageUpdateDto update(final BioPageButtonDto... buttons) {
        return BioPageUpdateDto.builder()
                .displayName("Ana Horvat")
                .bio("Photos from Zagreb")
                .avatarKind(BioAvatarKind.INITIALS)
                .accent(BioAccent.TEAL)
                .theme(BioTheme.DARK)
                .buttons(List.of(buttons))
                .build();
    }

    private static DataIntegrityViolationException violation(final String constraint) {
        return new DataIntegrityViolationException("could not execute statement", new ConstraintViolationException(
                "could not execute statement", new SQLException("duplicate key value violates unique constraint \"" + constraint + "\"", "23505"),
                constraint));
    }

    private static String codePoints(final int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

}
