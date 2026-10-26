package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.BioHandleUnavailableException;
import cc.hrva.urlshortener.model.BioHandleHold;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.service.ReservedAliasService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BioHandlesTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-26T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final String LENGTH = "A handle must be 3 to 30 characters.";
    private static final String CHARACTERS = "Use only letters, numbers, hyphens and underscores.";
    private static final String NO_LETTER_OR_NUMBER = "A handle needs at least one letter or number.";
    private static final String RESERVED = "This handle is reserved. Try another.";
    private static final String TAKEN = "This handle is taken. Try another.";

    @Mock
    private ReservedAliasService reservedAliasService;

    @Mock
    private BioPageRepository bioPageRepository;

    @Mock
    private BioHandleHoldRepository bioHandleHoldRepository;

    private BioHandles bioHandles;

    @BeforeEach
    void setUp() {
        bioHandles = new BioHandles(reservedAliasService, bioPageRepository, bioHandleHoldRepository,
                Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
    }

    @Test
    void normalizesCaseSpacesAndOneAt() {
        assertThat(BioHandles.normalize(" @Ana ")).isEqualTo("ana");
        assertThat(BioHandles.normalize("ANA")).isEqualTo("ana");
        assertThat(BioHandles.normalize("@@ana")).isEqualTo("@ana");
        assertThat(BioHandles.normalize(null)).isEmpty();
        assertThat(BioHandles.formatError(BioHandles.normalize("@@ana"))).contains(CHARACTERS);
    }

    @ParameterizedTest
    @MethodSource("formats")
    void formatErrors(final String handle, final String error) {
        assertThat(BioHandles.formatError(handle)).isEqualTo(Optional.ofNullable(error));
    }

    static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of("", LENGTH),
                Arguments.of("a", LENGTH),
                Arguments.of("ab", LENGTH),
                Arguments.of("a".repeat(31), LENGTH),
                Arguments.of("ana@", CHARACTERS),
                Arguments.of("čokolada", CHARACTERS),
                Arguments.of("ana horvat", CHARACTERS),
                Arguments.of("---", NO_LETTER_OR_NUMBER),
                Arguments.of("_-_", NO_LETTER_OR_NUMBER),
                Arguments.of("ana", null),
                Arguments.of("a".repeat(30), null),
                Arguments.of("ana-horvat_1", null),
                Arguments.of("123", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "promo", "admin", "help", "support", "hrva", "hrva-support", "hrvalabs_team", "hrvacc-official", "_hrva"})
    void reservedAliasesAndSystemWordsAreReserved(final String handle) {
        final var reservedAliases = Set.of("api", "promo");
        when(reservedAliasService.isReserved(anyString())).thenAnswer(invocation -> reservedAliases.contains(invocation.getArgument(0, String.class)));

        assertThat(bioHandles.unavailableReason(handle, null)).contains(RESERVED);
    }

    @Test
    void hrvatskaIsNotReserved() {
        assertThat(bioHandles.unavailableReason("hrvatska", null)).isEmpty();
        assertThat(bioHandles.unavailableReason("ana-hrva", null)).isEmpty();
    }

    @Test
    void anotherPagesHandleIsTaken() {
        when(bioPageRepository.findByHandle("ana")).thenReturn(Optional.of(page(2L)));

        assertThat(bioHandles.unavailableReason("ana", page(1L))).contains(TAKEN);
        assertThat(bioHandles.unavailableReason("ana", null)).contains(TAKEN);
    }

    @Test
    void thePagesOwnCurrentHandleIsAvailableToIt() {
        when(bioPageRepository.findByHandle("ana")).thenReturn(Optional.of(page(1L)));

        assertThat(bioHandles.unavailableReason("ana", page(1L))).isEmpty();
    }

    @Test
    void aHandleHeldForAnotherPageIsTaken() {
        when(bioHandleHoldRepository.findByHandleAndHeldUntilAfter("ana", NOW)).thenReturn(Optional.of(hold("ana", page(2L))));

        assertThat(bioHandles.unavailableReason("ana", page(1L))).contains(TAKEN);
        assertThat(bioHandles.unavailableReason("ana", null)).contains(TAKEN);
    }

    @Test
    void anUnlinkedHoldIsTaken() {
        when(bioHandleHoldRepository.findByHandleAndHeldUntilAfter("ana", NOW)).thenReturn(Optional.of(hold("ana", null)));

        assertThat(bioHandles.unavailableReason("ana", page(1L))).contains(TAKEN);
    }

    @Test
    void thePagesOwnHeldHandleIsAvailableToIt() {
        when(bioHandleHoldRepository.findByHandleAndHeldUntilAfter("ana", NOW)).thenReturn(Optional.of(hold("ana", page(1L))));

        assertThat(bioHandles.unavailableReason("ana", page(1L))).isEmpty();
    }

    @Test
    void anExpiredHoldDoesNotBlock() {
        assertThat(bioHandles.unavailableReason("ana", page(1L))).isEmpty();

        verify(bioHandleHoldRepository).findByHandleAndHeldUntilAfter("ana", NOW);
    }

    @Test
    void requireAvailableRefusesTheFormatFirstThenAnUnavailableHandle() {
        assertThatThrownBy(() -> bioHandles.requireAvailable("a", null))
                .isInstanceOf(ApiException.class)
                .hasMessage(LENGTH);
        assertThatThrownBy(() -> bioHandles.requireAvailable("admin", null))
                .isInstanceOf(BioHandleUnavailableException.class)
                .hasMessage(RESERVED);

        when(bioPageRepository.findByHandle("ana")).thenReturn(Optional.of(page(2L)));
        assertThatThrownBy(() -> bioHandles.requireAvailable("ana", page(1L)))
                .isInstanceOf(BioHandleUnavailableException.class)
                .hasMessage(TAKEN);
    }

    @Test
    void requireAvailableLetsAFreeHandleThrough() {
        bioHandles.requireAvailable("ana", null);

        verify(reservedAliasService).isReserved("ana");
    }

    @Test
    void holdLastsThirtyDays() {
        final var page = page(1L);

        bioHandles.hold("ana", page);
        bioHandles.hold("old", null);

        final var holds = ArgumentCaptor.forClass(BioHandleHold.class);
        verify(bioHandleHoldRepository, times(2)).save(holds.capture());
        assertThat(holds.getAllValues())
                .extracting(BioHandleHold::getHandle, BioHandleHold::getPage, BioHandleHold::getHeldUntil, BioHandleHold::getCreatedAt)
                .containsExactly(
                        tuple("ana", page, NOW.plusDays(30), NOW),
                        tuple("old", null, NOW.plusDays(30), NOW));
        assertThat(BioHandles.HOLD).hasDays(30);
    }

    @Test
    void releaseOnlyDeletesTheCallersHold() {
        final var ownHold = hold("own", page(1L));
        when(bioHandleHoldRepository.findById("own")).thenReturn(Optional.of(ownHold));
        when(bioHandleHoldRepository.findById("other")).thenReturn(Optional.of(hold("other", page(2L))));
        when(bioHandleHoldRepository.findById("unlinked")).thenReturn(Optional.of(hold("unlinked", null)));
        when(bioHandleHoldRepository.findById("none")).thenReturn(Optional.empty());

        bioHandles.releaseOwnHold("other", page(1L));
        bioHandles.releaseOwnHold("unlinked", page(1L));
        bioHandles.releaseOwnHold("none", page(1L));
        verify(bioHandleHoldRepository, never()).delete(any());

        bioHandles.releaseOwnHold("own", page(1L));
        verify(bioHandleHoldRepository).delete(ownHold);
    }

    private static BioPage page(final Long id) {
        return BioPage.builder().id(id).build();
    }

    private static BioHandleHold hold(final String handle, final BioPage page) {
        return BioHandleHold.builder().handle(handle).page(page).heldUntil(NOW.plusDays(1)).createdAt(NOW.minusDays(29)).build();
    }

}
