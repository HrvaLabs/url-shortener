package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.ReservedAliasResponse;
import cc.hrva.urlshortener.dto.ReservedAliasResponse.InUseLink;
import cc.hrva.urlshortener.exception.ReservedAliasNotFoundException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.model.ReservedAlias;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ReservedAliasRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultReservedAliasService;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultReservedAliasServiceTest {

    private static final String ADMIN = "admin@hrva.cc";
    private static final String ALREADY_RESERVED = "This alias is already reserved.";
    private static final String BUILT_IN_REASON = "Used by hrva.cc itself";
    private static final LocalDateTime RESERVED_AT = LocalDateTime.of(2026, 10, 25, 10, 0);

    @Mock
    private ReservedAliasRepository reservedAliasRepository;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UserService userService;

    @Captor
    private ArgumentCaptor<Collection<String>> aliases;

    private ReservedAliasService reservedAliasService;

    @BeforeEach
    void setUp() {
        reservedAliasService = new DefaultReservedAliasService(reservedAliasRepository, urlRepository, userService);
    }

    @Test
    void builtInWordsAreReservedInAnyCase() {
        assertThat(reservedAliasService.isReserved("API")).isTrue();
        assertThat(reservedAliasService.isReserved("mcp")).isTrue();
        assertThat(reservedAliasService.isReserved(" Login ")).isTrue();

        verify(reservedAliasRepository, never()).existsByAlias(anyString());
    }

    @Test
    void customAliasesAreReservedInAnyCase() {
        when(reservedAliasRepository.existsByAlias("promo")).thenReturn(true);

        assertThat(reservedAliasService.isReserved("Promo")).isTrue();
        assertThat(reservedAliasService.isReserved(" promo ")).isTrue();
    }

    @Test
    void freeAndNullAliasesAreNotReserved() {
        when(reservedAliasRepository.existsByAlias("summer")).thenReturn(false);

        assertThat(reservedAliasService.isReserved("Summer")).isFalse();
        assertThat(reservedAliasService.isReserved(null)).isFalse();

        verify(reservedAliasRepository).existsByAlias("summer");
        verifyNoMoreInteractions(reservedAliasRepository);
    }

    @Test
    void listsBuiltInsFirstThenCustomWithTheLinksUsingThem() {
        final var promo = reservedAlias(3L, "promo", "Kept for the spring campaign");
        final var owner = User.builder().id(8L).email("ana@example.com").build();
        when(reservedAliasRepository.findAllByOrderByAliasAsc()).thenReturn(List.of(promo));
        when(urlRepository.findByLowerShortUrlIn(anyCollection())).thenReturn(List.of(
                Url.builder().id(41L).shortUrl("Promo").owner(owner).active(true).build(),
                Url.builder().id(52L).shortUrl("Login").active(false).build()));

        final var entries = reservedAliasService.list();

        assertThat(entries).extracting(ReservedAliasResponse::alias).containsExactly(
                "actuator", "api", "auth", "error", "login", "logout", "mcp", "oauth2", "swagger-ui", "v3", "webjars", "promo");
        assertThat(entries.subList(0, 11)).allSatisfy(entry -> {
            assertThat(entry.id()).isNull();
            assertThat(entry.builtIn()).isTrue();
            assertThat(entry.reason()).isEqualTo(BUILT_IN_REASON);
            assertThat(entry.createdAt()).isNull();
            assertThat(entry.createdBy()).isNull();
        });
        assertThat(entries.get(4).inUse()).containsExactly(new InUseLink(52L, "Login", null, false));
        assertThat(entries.getLast()).isEqualTo(new ReservedAliasResponse(3L, "promo", "Kept for the spring campaign", false, RESERVED_AT, ADMIN,
                List.of(new InUseLink(41L, "Promo", "ana@example.com", true))));
        assertThat(entries).filteredOn(entry -> !List.of("login", "promo").contains(entry.alias()))
                .allSatisfy(entry -> assertThat(entry.inUse()).isEmpty());

        verify(urlRepository).findByLowerShortUrlIn(aliases.capture());
        assertThat(aliases.getValue()).containsExactlyInAnyOrder(
                "actuator", "api", "auth", "error", "login", "logout", "mcp", "oauth2", "swagger-ui", "v3", "webjars", "promo");
    }

    @Test
    void reserveStoresTheAliasLowerCasedWithTheAdmin() {
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).email(ADMIN).build());
        when(reservedAliasRepository.save(any(ReservedAlias.class))).thenAnswer(invocation -> {
            final var saved = invocation.<ReservedAlias>getArgument(0);
            saved.setId(3L);
            saved.setCreatedAt(RESERVED_AT);
            return saved;
        });

        final var response = reservedAliasService.reserve(new ReservedAliasDto("Promo", "  Spring campaign "));

        final var saved = ArgumentCaptor.forClass(ReservedAlias.class);
        verify(reservedAliasRepository).save(saved.capture());
        assertThat(saved.getValue())
                .extracting(ReservedAlias::getAlias, ReservedAlias::getReason, ReservedAlias::getCreatedBy)
                .containsExactly("promo", "Spring campaign", ADMIN);
        assertThat(response).isEqualTo(new ReservedAliasResponse(3L, "promo", "Spring campaign", false, RESERVED_AT, ADMIN, List.of()));
    }

    @Test
    void reserveRefusesABuiltInWord() {
        assertThatThrownBy(() -> reservedAliasService.reserve(new ReservedAliasDto("MCP", "Spring campaign")))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage(ALREADY_RESERVED);

        verify(reservedAliasRepository, never()).save(any());
        verifyNoInteractions(urlRepository, userService);
    }

    @Test
    void reserveRefusesADuplicate() {
        when(reservedAliasRepository.existsByAlias("promo")).thenReturn(true);

        assertThatThrownBy(() -> reservedAliasService.reserve(new ReservedAliasDto("Promo", "Spring campaign")))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage(ALREADY_RESERVED);

        verify(reservedAliasRepository, never()).save(any());
    }

    @Test
    void aLostInsertRaceIsTheSameRefusal() {
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).email(ADMIN).build());
        when(reservedAliasRepository.save(any(ReservedAlias.class)))
                .thenThrow(new DataIntegrityViolationException("Unique index or primary key violation: UX_RESERVED_ALIAS_ALIAS"));

        assertThatThrownBy(() -> reservedAliasService.reserve(new ReservedAliasDto("promo", "Spring campaign")))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage(ALREADY_RESERVED);
    }

    @Test
    void reservingAnAliasInUseChangesNoLink() {
        final var link = Url.builder().id(41L).shortUrl("Promo").active(true).build();
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).email(ADMIN).build());
        when(reservedAliasRepository.save(any(ReservedAlias.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(urlRepository.findByLowerShortUrlIn(List.of("promo"))).thenReturn(List.of(link));

        final var response = reservedAliasService.reserve(new ReservedAliasDto("promo", "Spring campaign"));

        assertThat(response.inUse()).containsExactly(new InUseLink(41L, "Promo", null, true));
        assertThat(link.isActive()).isTrue();
        verify(urlRepository).findByLowerShortUrlIn(List.of("promo"));
        verifyNoMoreInteractions(urlRepository);
    }

    @Test
    void unreserveDeletesACustomEntry() {
        final var promo = reservedAlias(3L, "promo", "Spring campaign");
        when(reservedAliasRepository.findById(3L)).thenReturn(Optional.of(promo));

        reservedAliasService.unreserve(3L);

        verify(reservedAliasRepository).delete(promo);
    }

    @Test
    void unreservingAnUnknownIdIsNotFound() {
        when(reservedAliasRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservedAliasService.unreserve(99L))
                .isInstanceOf(ReservedAliasNotFoundException.class)
                .hasMessage("Reserved alias not found");

        verify(reservedAliasRepository, never()).delete(any());
    }

    private static ReservedAlias reservedAlias(final long id, final String alias, final String reason) {
        return ReservedAlias.builder().id(id).alias(alias).reason(reason).createdAt(RESERVED_AT).createdBy(ADMIN).build();
    }

}
