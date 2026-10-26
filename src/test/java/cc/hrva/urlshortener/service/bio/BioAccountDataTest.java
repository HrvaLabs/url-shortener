package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.dto.DataExportDto.BioButtonExport;
import cc.hrva.urlshortener.dto.DataExportDto.BioPageExport;
import cc.hrva.urlshortener.model.BioHandleHold;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioIcon;
import cc.hrva.urlshortener.model.enums.BioTheme;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BioAccountDataTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-26T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 20, 8, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 10, 25, 18, 30);
    private static final String CAMERA = codePoints(0x1F4F7);

    @Mock
    private BioPageRepository bioPageRepository;

    @Mock
    private BioPageLock bioPageLock;

    @Mock
    private BioPageButtonRepository bioPageButtonRepository;

    @Mock
    private BioHandleHoldRepository bioHandleHoldRepository;

    private BioAccountData bioAccountData;
    private User owner;
    private BioPage page;

    @BeforeEach
    void setUp() {
        bioAccountData = new BioAccountData(bioPageRepository, bioPageLock, bioPageButtonRepository, bioHandleHoldRepository,
                Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
        owner = User.builder().id(7L).email("ana@example.com").build();
        page = BioPage.builder()
                .id(12L)
                .owner(owner)
                .handle("ana")
                .displayName("Ana Horvat")
                .bio("Photos from Zagreb")
                .avatarKind(BioAvatarKind.EMOJI)
                .avatarEmoji(CAMERA)
                .accent(BioAccent.TEAL)
                .theme(BioTheme.DARK)
                .indexable(true)
                .createdAt(CREATED_AT)
                .updatedAt(UPDATED_AT)
                .build();
    }

    @Test
    void exportListsTheButtonsInOrderWithAliases() {
        when(bioPageRepository.findByOwner(owner)).thenReturn(Optional.of(page));
        when(bioPageButtonRepository.findByPageWithUrl(page)).thenReturn(List.of(
                button("Shop", BioIcon.INSTAGRAM, "ana-shop", true),
                button("Old blog", null, "ana-blog", false),
                button("Mail me", BioIcon.EMAIL, "ana-mail", true)));

        assertThat(bioAccountData.exportFor(owner)).contains(new BioPageExport(
                "ana",
                "Ana Horvat",
                "Photos from Zagreb",
                BioAvatarKind.EMOJI,
                CAMERA,
                BioAccent.TEAL,
                BioTheme.DARK,
                true,
                false,
                null,
                List.of(
                        new BioButtonExport("Shop", BioIcon.INSTAGRAM, "ana-shop", true),
                        new BioButtonExport("Old blog", null, "ana-blog", false),
                        new BioButtonExport("Mail me", BioIcon.EMAIL, "ana-mail", true)),
                CREATED_AT,
                UPDATED_AT));
    }

    @Test
    void aHiddenPageExportsTheReasonTheOwnerSeesButNotTheAdmin() {
        page.setHiddenAt(NOW.minusDays(1));
        page.setHiddenReason("Impersonation");
        page.setHiddenBy("admin@hrva.cc");
        when(bioPageRepository.findByOwner(owner)).thenReturn(Optional.of(page));
        when(bioPageButtonRepository.findByPageWithUrl(page)).thenReturn(List.of());

        final var export = bioAccountData.exportFor(owner).orElseThrow();

        assertThat(export.hidden()).isTrue();
        assertThat(export.hiddenReason()).isEqualTo("Impersonation");
        assertThat(export.buttons()).isEmpty();
    }

    @Test
    void theExportCarriesNoModerationNotesIdsOrViewCounts() {
        assertThat(componentNames(BioPageExport.class)).containsExactly(
                "handle", "displayName", "bio", "avatarKind", "avatarEmoji", "accent", "theme", "indexable", "hidden", "hiddenReason",
                "buttons", "createdAt", "updatedAt");
        assertThat(componentNames(BioButtonExport.class)).containsExactly("title", "icon", "shortUrl", "visible");
    }

    @Test
    void noPageExportsNothing() {
        when(bioPageRepository.findByOwner(owner)).thenReturn(Optional.empty());

        assertThat(bioAccountData.exportFor(owner)).isEmpty();
        verifyNoInteractions(bioPageButtonRepository);
    }

    @Test
    void deletingHoldsTheHandleUnlinkedThenDeletes() {
        when(bioPageRepository.findByOwner(owner)).thenReturn(Optional.of(page));
        when(bioPageLock.lock(page)).thenReturn(Optional.of(page));

        bioAccountData.deleteFor(owner);

        final var hold = ArgumentCaptor.forClass(BioHandleHold.class);
        final var inOrder = inOrder(bioPageLock, bioHandleHoldRepository, bioPageRepository);
        inOrder.verify(bioPageLock).lock(page);
        inOrder.verify(bioHandleHoldRepository).save(hold.capture());
        inOrder.verify(bioPageRepository).delete(page);
        assertThat(hold.getValue().getHandle()).isEqualTo("ana");
        assertThat(hold.getValue().getPage()).isNull();
        assertThat(hold.getValue().getHeldUntil()).isEqualTo(NOW.plusDays(30));
        assertThat(hold.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void theHandleIsReadAfterTheLock() {
        final var lockedPage = BioPage.builder().id(12L).owner(owner).handle("ana-new").build();
        when(bioPageRepository.findByOwner(owner)).thenReturn(Optional.of(page));
        when(bioPageLock.lock(page)).thenReturn(Optional.of(lockedPage));

        bioAccountData.deleteFor(owner);

        final var hold = ArgumentCaptor.forClass(BioHandleHold.class);
        verify(bioHandleHoldRepository).save(hold.capture());
        assertThat(hold.getValue().getHandle()).isEqualTo("ana-new");
        verify(bioPageRepository).delete(lockedPage);
    }

    @Test
    void deletingAPageWithoutAHandleHoldsNothing() {
        page.setHandle(null);
        page.setHandleRevokedReason("Impersonation");
        when(bioPageRepository.findByOwner(owner)).thenReturn(Optional.of(page));
        when(bioPageLock.lock(page)).thenReturn(Optional.of(page));

        bioAccountData.deleteFor(owner);

        verifyNoInteractions(bioHandleHoldRepository);
        verify(bioPageRepository).delete(page);
    }

    @Test
    void aHiddenPageIsDeletedWithItsAccount() {
        page.setHiddenAt(NOW.minusDays(1));
        when(bioPageRepository.findByOwner(owner)).thenReturn(Optional.of(page));
        when(bioPageLock.lock(page)).thenReturn(Optional.of(page));

        bioAccountData.deleteFor(owner);

        verify(bioHandleHoldRepository).save(any(BioHandleHold.class));
        verify(bioPageRepository).delete(page);
    }

    @Test
    void noPageDeletesNothing() {
        when(bioPageRepository.findByOwner(owner)).thenReturn(Optional.empty());

        bioAccountData.deleteFor(owner);

        verifyNoInteractions(bioPageLock, bioHandleHoldRepository);
        verify(bioPageRepository, never()).delete(any(BioPage.class));
    }

    @Test
    void aPageDeletedMeanwhileIsLeftAlone() {
        when(bioPageRepository.findByOwner(owner)).thenReturn(Optional.of(page));
        when(bioPageLock.lock(page)).thenReturn(Optional.empty());

        bioAccountData.deleteFor(owner);

        verifyNoInteractions(bioHandleHoldRepository);
        verify(bioPageRepository, never()).delete(any(BioPage.class));
    }

    private BioPageButton button(final String title, final BioIcon icon, final String shortUrl, final boolean visible) {
        return BioPageButton.builder()
                .page(page)
                .url(Url.builder().shortUrl(shortUrl).longUrl("https://example.org/" + shortUrl).build())
                .title(title)
                .icon(icon)
                .visible(visible)
                .build();
    }

    private static List<String> componentNames(final Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static String codePoints(final int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

}
