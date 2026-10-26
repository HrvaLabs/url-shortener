package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.bio.AdminBioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioPageReasonDto;
import cc.hrva.urlshortener.dto.bio.BioPageReviewResolveDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.BioHandleUnavailableException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioPageReviewAction;
import cc.hrva.urlshortener.model.enums.BioPageReviewResolution;
import cc.hrva.urlshortener.model.enums.BioTheme;
import cc.hrva.urlshortener.repository.BioButtonCount;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.service.bio.BioHandles;
import cc.hrva.urlshortener.service.bio.BioPageLock;
import cc.hrva.urlshortener.service.impl.DefaultAdminBioPageService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAdminBioPageServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-26T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 20, 8, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 10, 25, 18, 30);
    private static final String ADMIN_EMAIL = "admin@hrva.cc";
    private static final String NOT_FOUND = "Bio page not found";
    private static final String NO_HANDLE = "This page has no handle.";
    private static final String NOT_IN_QUEUE = "This bio page is no longer in the review queue.";
    private static final String REASON_REQUIRED = "Reason is required";
    private static final String TAKEN = "This handle is taken. Try another.";
    private static final String RESERVED = "This handle is reserved. Try another.";
    private static final String ALREADY_RESERVED = "This alias is already reserved.";

    @Mock
    private BioPageRepository bioPageRepository;

    @Mock
    private BioPageLock bioPageLock;

    @Mock
    private BioPageButtonRepository bioPageButtonRepository;

    @Mock
    private BioPageReviewService bioPageReviewService;

    @Mock
    private BioHandles bioHandles;

    @Mock
    private ReservedAliasService reservedAliasService;

    @Mock
    private UserService userService;

    private final User admin = User.builder().id(1L).email(ADMIN_EMAIL).active(true).build();
    private final User ana = User.builder().id(7L).email("ana@example.com").active(true).build();
    private BioPage page;
    private AdminBioPageService adminBioPageService;

    @BeforeEach
    void setUp() {
        page = page(12L, ana, "ana");
        lenient().when(userService.getUserFromToken()).thenReturn(admin);
        lenient().when(bioPageRepository.findById(12L)).thenReturn(Optional.of(page));
        lenient().when(bioPageLock.lock(any(BioPage.class))).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        lenient().when(bioPageButtonRepository.countByPageIds(List.of(12L))).thenReturn(List.of(new BioButtonCount(12L, 3L, 2L)));
        lenient().when(bioPageReviewService.openPageIds(List.of(12L))).thenReturn(Set.of());
        adminBioPageService = new DefaultAdminBioPageService(bioPageRepository, bioPageLock, bioPageButtonRepository, bioPageReviewService,
                bioHandles, reservedAliasService, userService, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void hideStoresTheReasonAndAdminAndResolvesReviews() {
        final var response = adminBioPageService.hide(12L, new BioPageReasonDto(" Impersonation "));

        final var inOrder = inOrder(bioPageLock, bioPageRepository, bioPageReviewService);
        inOrder.verify(bioPageLock).lock(page);
        inOrder.verify(bioPageRepository).saveAndFlush(page);
        inOrder.verify(bioPageReviewService).resolveOpen(page, BioPageReviewResolution.HIDDEN, ADMIN_EMAIL);
        assertThat(page.getHiddenAt()).isEqualTo(NOW);
        assertThat(page.getHiddenReason()).isEqualTo("Impersonation");
        assertThat(page.getHiddenBy()).isEqualTo(ADMIN_EMAIL);
        assertThat(response.hidden()).isTrue();
        assertThat(response.hiddenAt()).isEqualTo(NOW);
        assertThat(response.hiddenReason()).isEqualTo("Impersonation");
        assertThat(response.hiddenBy()).isEqualTo(ADMIN_EMAIL);
        assertThat(response.buttons()).isEqualTo(3L);
        assertThat(response.visibleButtons()).isEqualTo(2L);
    }

    @Test
    void hidingTwiceTakesTheNewReasonAndResolvesAgain() {
        page.setHiddenAt(UPDATED_AT);
        page.setHiddenReason("Spam");
        page.setHiddenBy("other-admin@hrva.cc");

        final var response = adminBioPageService.hide(12L, new BioPageReasonDto("Impersonation"));

        assertThat(response.hiddenReason()).isEqualTo("Impersonation");
        assertThat(response.hiddenBy()).isEqualTo(ADMIN_EMAIL);
        assertThat(response.hiddenAt()).isEqualTo(NOW);
        verify(bioPageReviewService).resolveOpen(page, BioPageReviewResolution.HIDDEN, ADMIN_EMAIL);
    }

    @Test
    void hidingPublishesNothing() {
        assertThat(Arrays.stream(DefaultAdminBioPageService.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes())))
                .doesNotContain(ApplicationEventPublisher.class);
    }

    @Test
    void unhideClearsAllThreeFields() {
        page.setHiddenAt(UPDATED_AT);
        page.setHiddenReason("Spam");
        page.setHiddenBy("other-admin@hrva.cc");

        final var response = adminBioPageService.unhide(12L);

        final var inOrder = inOrder(bioPageLock, bioPageRepository);
        inOrder.verify(bioPageLock).lock(page);
        inOrder.verify(bioPageRepository).saveAndFlush(page);
        assertThat(page.getHiddenAt()).isNull();
        assertThat(page.getHiddenReason()).isNull();
        assertThat(page.getHiddenBy()).isNull();
        assertThat(response.hidden()).isFalse();
        assertThat(response.hiddenAt()).isNull();
        assertThat(response.hiddenReason()).isNull();
        assertThat(response.hiddenBy()).isNull();
        verify(bioPageReviewService, never()).resolveOpen(any(), any(), anyString());
    }

    @Test
    void changeHandleHoldsTheOldOneAndClearsARevokedReason() {
        final var response = adminBioPageService.changeHandle(12L, new BioHandleDto(" @Ana-Horvat "));

        final var inOrder = inOrder(bioPageLock, bioHandles, bioPageRepository);
        inOrder.verify(bioPageLock).lock(page);
        inOrder.verify(bioHandles).requireAvailable("ana-horvat", page);
        inOrder.verify(bioHandles).releaseOwnHold("ana-horvat", page);
        inOrder.verify(bioHandles).hold("ana", page);
        inOrder.verify(bioPageRepository).saveAndFlush(page);
        assertThat(page.getHandle()).isEqualTo("ana-horvat");
        assertThat(response.handle()).isEqualTo("ana-horvat");
    }

    @Test
    void changeHandleOfARevokedPageClearsTheReasonAndHoldsNothing() {
        page.setHandle(null);
        page.setHandleRevokedReason("Impersonation");

        final var response = adminBioPageService.changeHandle(12L, new BioHandleDto("ana-horvat"));

        assertThat(page.getHandle()).isEqualTo("ana-horvat");
        assertThat(page.getHandleRevokedReason()).isNull();
        assertThat(response.handleRevokedReason()).isNull();
        verify(bioHandles, never()).hold(anyString(), any());
        verify(bioPageRepository).saveAndFlush(page);
    }

    @Test
    void changeHandleToTheSameHandleWritesNothing() {
        final var response = adminBioPageService.changeHandle(12L, new BioHandleDto("ANA"));

        assertThat(response.handle()).isEqualTo("ana");
        verifyNoInteractions(bioHandles);
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void aRefusedHandleWritesNothing() {
        doThrow(new BioHandleUnavailableException(RESERVED)).when(bioHandles).requireAvailable("admin", page);

        assertThatThrownBy(() -> adminBioPageService.changeHandle(12L, new BioHandleDto("admin")))
                .isInstanceOf(BioHandleUnavailableException.class)
                .hasMessage(RESERVED);

        assertThat(page.getHandle()).isEqualTo("ana");
        verify(bioHandles, never()).hold(anyString(), any());
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void aLostRenameRaceIs409() {
        when(bioPageRepository.saveAndFlush(page)).thenThrow(new DataIntegrityViolationException("uq_bio_page_handle"));

        assertThatThrownBy(() -> adminBioPageService.changeHandle(12L, new BioHandleDto("bob")))
                .isInstanceOf(BioHandleUnavailableException.class)
                .hasMessage(TAKEN);
    }

    @Test
    void revokeClearsTheHandleAndReservesIt() {
        final var response = adminBioPageService.revokeHandle(12L, new BioPageReasonDto(" Impersonation "));

        final var inOrder = inOrder(bioPageLock, bioPageRepository, reservedAliasService);
        inOrder.verify(bioPageLock).lock(page);
        inOrder.verify(bioPageRepository).saveAndFlush(page);
        inOrder.verify(reservedAliasService).reserve(new ReservedAliasDto("ana", "Revoked bio page handle: Impersonation"), ADMIN_EMAIL);
        assertThat(page.getHandle()).isNull();
        assertThat(page.getHandleRevokedReason()).isEqualTo("Impersonation");
        assertThat(response.handle()).isNull();
        assertThat(response.handleRevokedReason()).isEqualTo("Impersonation");
        verify(bioHandles, never()).hold(anyString(), any());
    }

    @Test
    void revokeIgnoresAnAlreadyReservedHandle() {
        when(reservedAliasService.reserve(any(ReservedAliasDto.class), anyString())).thenThrow(new ShortUrlAlreadyExistsException(ALREADY_RESERVED));

        final var response = adminBioPageService.revokeHandle(12L, new BioPageReasonDto("Impersonation"));

        assertThat(response.handle()).isNull();
        assertThat(page.getHandleRevokedReason()).isEqualTo("Impersonation");
        verify(bioPageRepository).saveAndFlush(page);
    }

    @Test
    void revokeDoesNotTryToReserveAHandleThatIsAlreadyReserved() {
        when(reservedAliasService.isReserved("ana")).thenReturn(true);

        adminBioPageService.revokeHandle(12L, new BioPageReasonDto("Impersonation"));

        verify(reservedAliasService, never()).reserve(any(), anyString());
        assertThat(page.getHandle()).isNull();
    }

    @Test
    void aFailedReservationFailsTheRevoke() {
        final var failure = new IllegalStateException("database down");
        when(reservedAliasService.reserve(any(ReservedAliasDto.class), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> adminBioPageService.revokeHandle(12L, new BioPageReasonDto("Impersonation"))).isSameAs(failure);
    }

    @Test
    void revokeWithoutAHandleIs400() {
        page.setHandle(null);

        assertThatThrownBy(() -> adminBioPageService.revokeHandle(12L, new BioPageReasonDto("Impersonation")))
                .isInstanceOf(ApiException.class)
                .hasMessage(NO_HANDLE);

        verify(bioPageRepository, never()).saveAndFlush(any());
        verifyNoInteractions(reservedAliasService);
    }

    @Test
    void aReasonOver200IsCutForTheReservation() {
        final var reason = "r".repeat(200);

        adminBioPageService.revokeHandle(12L, new BioPageReasonDto(reason));

        final var reserved = ArgumentCaptor.forClass(ReservedAliasDto.class);
        verify(reservedAliasService).reserve(reserved.capture(), anyString());
        assertThat(reserved.getValue().alias()).isEqualTo("ana");
        assertThat(reserved.getValue().reason()).hasSize(200).isEqualTo(("Revoked bio page handle: " + reason).substring(0, 200));
        assertThat(page.getHandleRevokedReason()).isEqualTo(reason);
    }

    @Test
    void resolveOkLeavesThePageUp() {
        when(bioPageReviewService.openPageIds(List.of(12L))).thenReturn(Set.of(12L));

        adminBioPageService.resolveReview(12L, new BioPageReviewResolveDto(BioPageReviewAction.OK, null));

        final var inOrder = inOrder(bioPageLock, bioPageReviewService);
        inOrder.verify(bioPageLock).lock(page);
        inOrder.verify(bioPageReviewService).resolveOpen(page, BioPageReviewResolution.OK, ADMIN_EMAIL);
        assertThat(page.getHiddenAt()).isNull();
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void resolveHideNeedsAReason() {
        when(bioPageReviewService.openPageIds(List.of(12L))).thenReturn(Set.of(12L));

        for (final var reason : Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(() -> adminBioPageService.resolveReview(12L, new BioPageReviewResolveDto(BioPageReviewAction.HIDE, reason)))
                    .isInstanceOf(ApiException.class)
                    .hasMessage(REASON_REQUIRED);
        }

        assertThat(page.getHiddenAt()).isNull();
        verify(bioPageRepository, never()).saveAndFlush(any());
        verify(bioPageReviewService, never()).resolveOpen(any(), any(), anyString());
    }

    @Test
    void resolveHideHidesThePageWithTheReason() {
        when(bioPageReviewService.openPageIds(List.of(12L))).thenReturn(Set.of(12L));

        adminBioPageService.resolveReview(12L, new BioPageReviewResolveDto(BioPageReviewAction.HIDE, " Phishing or scam "));

        final var inOrder = inOrder(bioPageLock, bioPageRepository, bioPageReviewService);
        inOrder.verify(bioPageLock).lock(page);
        inOrder.verify(bioPageRepository).saveAndFlush(page);
        inOrder.verify(bioPageReviewService).resolveOpen(page, BioPageReviewResolution.HIDDEN, ADMIN_EMAIL);
        assertThat(page.getHiddenAt()).isEqualTo(NOW);
        assertThat(page.getHiddenReason()).isEqualTo("Phishing or scam");
        assertThat(page.getHiddenBy()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void resolveWithoutAnOpenReviewIs400() {
        for (final var action : BioPageReviewAction.values()) {
            assertThatThrownBy(() -> adminBioPageService.resolveReview(12L, new BioPageReviewResolveDto(action, "Spam")))
                    .isInstanceOf(ApiException.class)
                    .hasMessage(NOT_IN_QUEUE);
        }

        verify(bioPageReviewService, never()).resolveOpen(any(), any(), anyString());
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void searchCountsButtonsAndOpenReviewsInTwoQueries() {
        final var pages = LongStream.rangeClosed(1, 20).mapToObj(id -> page(id, ana, "page-" + id)).toList();
        final var ids = LongStream.rangeClosed(1, 20).boxed().toList();
        when(bioPageRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(pages, invocation.getArgument(1), 45));
        when(bioPageButtonRepository.countByPageIds(ids)).thenReturn(List.of(new BioButtonCount(1L, 3L, 2L), new BioButtonCount(5L, 1L, 0L)));
        when(bioPageReviewService.openPageIds(ids)).thenReturn(Set.of(5L, 20L));

        final var result = adminBioPageService.search("ana", false, PageRequest.of(1, 20, Sort.by("handle")));

        assertThat(result.getTotalElements()).isEqualTo(45);
        assertThat(result.getContent()).hasSize(20);
        assertThat(result.getContent())
                .filteredOn(response -> List.of(1L, 2L, 5L, 20L).contains(response.id()))
                .extracting(AdminBioPageResponse::id, AdminBioPageResponse::buttons, AdminBioPageResponse::visibleButtons, AdminBioPageResponse::reviewOpen)
                .containsExactly(
                        tuple(1L, 3L, 2L, false),
                        tuple(2L, 0L, 0L, false),
                        tuple(5L, 1L, 0L, true),
                        tuple(20L, 0L, 0L, true));
        verify(bioPageButtonRepository).countByPageIds(ids);
        verify(bioPageReviewService).openPageIds(ids);
    }

    @Test
    void searchIsNewestFirstWhateverTheCallerAsks() {
        when(bioPageRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        adminBioPageService.search(null, true, PageRequest.of(2, 10, Sort.by("handle")));

        final var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(bioPageRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void anEmptyResultRunsNoCountQueries() {
        when(bioPageRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        assertThat(adminBioPageService.search("nobody", false, PageRequest.of(0, 20)).getContent()).isEmpty();

        verifyNoInteractions(bioPageButtonRepository, bioPageReviewService);
    }

    @Test
    void theResponseCarriesThePageAndItsOwner() {
        ana.setActive(false);
        when(bioPageReviewService.openPageIds(List.of(12L))).thenReturn(Set.of(12L));

        final var response = adminBioPageService.unhide(12L);

        assertThat(response).isEqualTo(new AdminBioPageResponse(12L, "ana", "Ana Horvat", "Photos from Zagreb", 7L, "ana@example.com", false,
                false, null, null, null, null, 3L, 2L, true, CREATED_AT, UPDATED_AT));
    }

    @Test
    void anOwnerWhoseActiveIsUnsetCountsAsActive() {
        ana.setActive(null);

        assertThat(adminBioPageService.unhide(12L).ownerActive()).isTrue();
    }

    @Test
    void anUnknownPageIs404() {
        when(bioPageRepository.findById(99L)).thenReturn(Optional.empty());

        expectNotFound(service -> service.hide(99L, new BioPageReasonDto("Spam")));
        expectNotFound(service -> service.unhide(99L));
        expectNotFound(service -> service.changeHandle(99L, new BioHandleDto("bob")));
        expectNotFound(service -> service.revokeHandle(99L, new BioPageReasonDto("Spam")));
        expectNotFound(service -> service.resolveReview(99L, new BioPageReviewResolveDto(BioPageReviewAction.OK, null)));
        verifyNoInteractions(bioPageLock, bioHandles, reservedAliasService);
    }

    @Test
    void aPageDeletedBeforeItWasLockedIs404() {
        when(bioPageLock.lock(page)).thenReturn(Optional.empty());

        expectNotFound(service -> service.hide(12L, new BioPageReasonDto("Spam")));
        expectNotFound(service -> service.revokeHandle(12L, new BioPageReasonDto("Spam")));
        verify(bioPageRepository, never()).saveAndFlush(any());
    }

    @Test
    void everyWriteRunsInAReadWriteTransaction() throws NoSuchMethodException {
        final var service = DefaultAdminBioPageService.class;
        assertThat(service.getAnnotation(Transactional.class).readOnly()).isTrue();
        for (final Method write : List.of(
                service.getMethod("hide", long.class, BioPageReasonDto.class),
                service.getMethod("unhide", long.class),
                service.getMethod("changeHandle", long.class, BioHandleDto.class),
                service.getMethod("revokeHandle", long.class, BioPageReasonDto.class),
                service.getMethod("resolveReview", long.class, BioPageReviewResolveDto.class))) {
            assertThat(write.getAnnotation(Transactional.class)).as(write.getName()).isNotNull();
            assertThat(write.getAnnotation(Transactional.class).readOnly()).as(write.getName()).isFalse();
        }
    }

    private void expectNotFound(final Consumer<AdminBioPageService> call) {
        assertThatThrownBy(() -> call.accept(adminBioPageService)).isInstanceOf(BioPageNotFoundException.class).hasMessage(NOT_FOUND);
    }

    private static BioPage page(final long id, final User owner, final String handle) {
        return BioPage.builder().id(id).owner(owner).handle(handle).displayName("Ana Horvat").bio("Photos from Zagreb")
                .avatarKind(BioAvatarKind.INITIALS).accent(BioAccent.BLUE).theme(BioTheme.AUTO)
                .createdAt(CREATED_AT).updatedAt(UPDATED_AT).build();
    }

}
