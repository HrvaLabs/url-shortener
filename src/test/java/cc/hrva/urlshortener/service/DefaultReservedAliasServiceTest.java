package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.ReservedAliasResponse;
import cc.hrva.urlshortener.dto.ReservedAliasResponse.InUseLink;
import cc.hrva.urlshortener.exception.InvalidSortException;
import cc.hrva.urlshortener.exception.ReservedAliasNotFoundException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.model.ReservedAlias;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.repository.ReservedAliasRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultReservedAliasService;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
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
    private static final String TARGET_TYPE = "ReservedAlias";

    @Mock
    private ReservedAliasRepository reservedAliasRepository;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private AuditLogService auditLogService;

    @Captor
    private ArgumentCaptor<Collection<String>> aliases;

    private final List<String> auditedInsideATransaction = new ArrayList<>();
    private boolean inTransaction;

    private ReservedAliasService reservedAliasService;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> recordIfInsideATransaction(invocation.getArgument(1)))
                .when(auditLogService).log(anyString(), any(AuditAction.class), anyString(), anyString(), anyString());
        lenient().doAnswer(invocation -> recordIfInsideATransaction(invocation.getArgument(1)))
                .when(auditLogService).logFailure(anyString(), any(AuditAction.class), anyString(), anyString(), anyString());
        reservedAliasService = new DefaultReservedAliasService(reservedAliasRepository, urlRepository, auditLogService, recordingTransactions());
    }

    @Test
    void builtInWordsAreReservedInAnyCase() {
        assertThat(reservedAliasService.isReserved("API")).isTrue();
        assertThat(reservedAliasService.isReserved("mcp")).isTrue();
        assertThat(reservedAliasService.isReserved(" Login ")).isTrue();
        assertThat(reservedAliasService.isReserved("ANON")).isTrue();

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
    void listsCustomFirstThenBuiltInsWithTheLinksUsingThem() {
        final var promo = reservedAlias(3L, "promo", "Kept for the spring campaign");
        final var owner = User.builder().id(8L).email("ana@example.com").build();
        when(reservedAliasRepository.findAllByOrderByAliasAsc()).thenReturn(List.of(promo));
        when(urlRepository.findByLowerShortUrlIn(anyCollection())).thenReturn(List.of(
                Url.builder().id(41L).shortUrl("Promo").owner(owner).active(true).build(),
                Url.builder().id(52L).shortUrl("Login").active(false).build()));

        final var entries = reservedAliasService.list(null, PageRequest.of(0, 20)).getContent();

        assertThat(entries).extracting(ReservedAliasResponse::alias).containsExactly(
                "promo", "actuator", "anon", "api", "auth", "error", "login", "logout", "mcp", "oauth2", "swagger-ui", "v3", "webjars");
        assertThat(entries.subList(1, 13)).allSatisfy(entry -> {
            assertThat(entry.id()).isNull();
            assertThat(entry.builtIn()).isTrue();
            assertThat(entry.reason()).isEqualTo(BUILT_IN_REASON);
            assertThat(entry.createdAt()).isNull();
            assertThat(entry.createdBy()).isNull();
        });
        assertThat(entries.get(6).inUse()).containsExactly(new InUseLink(52L, "Login", null, false));
        assertThat(entries.getFirst()).isEqualTo(new ReservedAliasResponse(3L, "promo", "Kept for the spring campaign", false, RESERVED_AT, ADMIN,
                List.of(new InUseLink(41L, "Promo", "ana@example.com", true))));
        assertThat(entries).filteredOn(entry -> !List.of("login", "promo").contains(entry.alias()))
                .allSatisfy(entry -> assertThat(entry.inUse()).isEmpty());

        verify(urlRepository).findByLowerShortUrlIn(aliases.capture());
        assertThat(aliases.getValue()).containsExactlyInAnyOrder(
                "actuator", "anon", "api", "auth", "error", "login", "logout", "mcp", "oauth2", "swagger-ui", "v3", "webjars", "promo");
    }

    @Test
    void aPageHoldsItsShareOfTheAliasesAndOnlyItsAliasesAreCheckedForLinks() {
        when(reservedAliasRepository.findAllByOrderByAliasAsc()).thenReturn(List.of(reservedAlias(3L, "promo", "Spring")));
        when(urlRepository.findByLowerShortUrlIn(anyCollection())).thenReturn(List.of());

        final var page = reservedAliasService.list(null, PageRequest.of(2, 5));

        assertThat(page.getTotalElements()).isEqualTo(13);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).extracting(ReservedAliasResponse::alias).containsExactly("swagger-ui", "v3", "webjars");
        verify(urlRepository).findByLowerShortUrlIn(aliases.capture());
        assertThat(aliases.getValue()).containsExactlyInAnyOrder("swagger-ui", "v3", "webjars");
    }

    @Test
    void theSearchMatchesTheAliasTheReasonOrWhoAddedItInAnyCase() {
        when(reservedAliasRepository.findAllByOrderByAliasAsc()).thenReturn(List.of(
                reservedAlias(3L, "promo", "Kept for the spring campaign"), reservedAlias(4L, "menu", "Gableci SPRING menu")));
        when(urlRepository.findByLowerShortUrlIn(anyCollection())).thenReturn(List.of());

        assertThat(reservedAliasService.list(" PRO ", PageRequest.of(0, 20)).getContent()).extracting(ReservedAliasResponse::alias)
                .containsExactly("promo");
        assertThat(reservedAliasService.list("spring", PageRequest.of(0, 20)).getContent()).extracting(ReservedAliasResponse::alias)
                .containsExactly("menu", "promo");
        assertThat(reservedAliasService.list("hrva.cc ITSELF", PageRequest.of(0, 20)).getTotalElements()).isEqualTo(12);
        assertThat(reservedAliasService.list("ADMIN@hrva", PageRequest.of(0, 20)).getContent()).extracting(ReservedAliasResponse::alias)
                .containsExactly("menu", "promo");
        assertThat(reservedAliasService.list("%", PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    void anEmptySearchFindsNoLinks() {
        when(reservedAliasRepository.findAllByOrderByAliasAsc()).thenReturn(List.of());

        assertThat(reservedAliasService.list("nothing like this", PageRequest.of(0, 20)).getContent()).isEmpty();

        verifyNoInteractions(urlRepository);
    }

    @Test
    void sortingByAliasMixesBuiltInAndCustomWords() {
        when(reservedAliasRepository.findAllByOrderByAliasAsc()).thenReturn(List.of(reservedAlias(3L, "promo", "Spring"), reservedAlias(4L, "b2b", "Sales")));
        when(urlRepository.findByLowerShortUrlIn(anyCollection())).thenReturn(List.of());

        assertThat(reservedAliasService.list(null, PageRequest.of(0, 4, Sort.by("alias"))).getContent()).extracting(ReservedAliasResponse::alias)
                .containsExactly("actuator", "anon", "api", "auth");
        assertThat(reservedAliasService.list(null, PageRequest.of(0, 4, Sort.by(Sort.Order.desc("alias")))).getContent())
                .extracting(ReservedAliasResponse::alias)
                .containsExactly("webjars", "v3", "swagger-ui", "promo");
        assertThat(reservedAliasService.list(null, PageRequest.of(1, 4, Sort.by("alias"))).getContent()).extracting(ReservedAliasResponse::alias)
                .containsExactly("b2b", "error", "login", "logout");
    }

    @Test
    void sortingByDateKeepsTheBuiltInWordsLastEitherWay() {
        final var older = ReservedAlias.builder().id(4L).alias("b2b").reason("Sales").createdAt(RESERVED_AT.minusDays(3)).createdBy(ADMIN).build();
        when(reservedAliasRepository.findAllByOrderByAliasAsc()).thenReturn(List.of(older, reservedAlias(3L, "promo", "Spring")));
        when(urlRepository.findByLowerShortUrlIn(anyCollection())).thenReturn(List.of());

        assertThat(reservedAliasService.list(null, PageRequest.of(0, 3, Sort.by(Sort.Order.desc("createdAt")))).getContent())
                .extracting(ReservedAliasResponse::alias)
                .containsExactly("promo", "b2b", "actuator");
        assertThat(reservedAliasService.list(null, PageRequest.of(0, 3, Sort.by(Sort.Order.asc("createdAt")))).getContent())
                .extracting(ReservedAliasResponse::alias)
                .containsExactly("b2b", "promo", "actuator");
    }

    @Test
    void sortingByAFieldOffTheListIsRefused() {
        assertThatThrownBy(() -> reservedAliasService.list(null, PageRequest.of(0, 20, Sort.by("createdBy"))))
                .isInstanceOf(InvalidSortException.class)
                .hasMessage("Sort must be alias or createdAt");
        verifyNoInteractions(reservedAliasRepository, urlRepository);
    }

    @Test
    void reserveStoresTheAliasLowerCasedWithTheAdmin() {
        when(reservedAliasRepository.save(any(ReservedAlias.class))).thenAnswer(invocation -> {
            final var saved = invocation.<ReservedAlias>getArgument(0);
            saved.setId(3L);
            saved.setCreatedAt(RESERVED_AT);
            return saved;
        });

        final var response = reservedAliasService.reserve(new ReservedAliasDto("Promo", "  Spring campaign "), ADMIN);

        final var saved = ArgumentCaptor.forClass(ReservedAlias.class);
        verify(reservedAliasRepository).save(saved.capture());
        assertThat(saved.getValue())
                .extracting(ReservedAlias::getAlias, ReservedAlias::getReason, ReservedAlias::getCreatedBy)
                .containsExactly("promo", "Spring campaign", ADMIN);
        assertThat(response).isEqualTo(new ReservedAliasResponse(3L, "promo", "Spring campaign", false, RESERVED_AT, ADMIN, List.of()));
    }

    @Test
    void aReservationIsAuditedWithTheStoredAliasAsTheTargetAndItsReason() {
        when(reservedAliasRepository.save(any(ReservedAlias.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reservedAliasService.reserve(new ReservedAliasDto("LongReason", "  Kept for the autumn campaign "), ADMIN);

        verify(auditLogService).log(ADMIN, AuditAction.RESERVE_ALIAS, TARGET_TYPE, "longreason", "reason: Kept for the autumn campaign");
        verifyNoMoreInteractions(auditLogService);
        assertThat(auditedInsideATransaction).isEmpty();
    }

    @Test
    void reserveRefusesABuiltInWord() {
        assertThatThrownBy(() -> reservedAliasService.reserve(new ReservedAliasDto("MCP", "Spring campaign"), ADMIN))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage(ALREADY_RESERVED);

        verify(reservedAliasRepository, never()).save(any());
        verifyNoInteractions(urlRepository);
    }

    @Test
    void reserveRefusesADuplicate() {
        when(reservedAliasRepository.existsByAlias("promo")).thenReturn(true);

        assertThatThrownBy(() -> reservedAliasService.reserve(new ReservedAliasDto("Promo", "Spring campaign"), ADMIN))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage(ALREADY_RESERVED);

        verify(reservedAliasRepository, never()).save(any());
    }

    @Test
    void aRefusedReservationIsAuditedAsFailedWithTheAttemptedAliasOutsideTheRolledBackTransaction() {
        when(reservedAliasRepository.existsByAlias("blog")).thenReturn(true);

        assertThatThrownBy(() -> reservedAliasService.reserve(new ReservedAliasDto("BLOG", "Spring campaign"), ADMIN))
                .isInstanceOf(ShortUrlAlreadyExistsException.class);
        assertThatThrownBy(() -> reservedAliasService.reserve(new ReservedAliasDto("Api", "Spring campaign"), ADMIN))
                .isInstanceOf(ShortUrlAlreadyExistsException.class);

        verify(auditLogService).logFailure(ADMIN, AuditAction.RESERVE_ALIAS, TARGET_TYPE, "blog", ALREADY_RESERVED);
        verify(auditLogService).logFailure(ADMIN, AuditAction.RESERVE_ALIAS, TARGET_TYPE, "api", ALREADY_RESERVED);
        verifyNoMoreInteractions(auditLogService);
        assertThat(auditedInsideATransaction).isEmpty();
    }

    @Test
    void aLostInsertRaceIsTheSameRefusal() {
        when(reservedAliasRepository.save(any(ReservedAlias.class)))
                .thenThrow(new DataIntegrityViolationException("Unique index or primary key violation: UX_RESERVED_ALIAS_ALIAS"));

        assertThatThrownBy(() -> reservedAliasService.reserve(new ReservedAliasDto("Promo", "Spring campaign"), ADMIN))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage(ALREADY_RESERVED);

        verify(auditLogService).logFailure(ADMIN, AuditAction.RESERVE_ALIAS, TARGET_TYPE, "promo", ALREADY_RESERVED);
        verifyNoMoreInteractions(auditLogService);
        assertThat(auditedInsideATransaction).isEmpty();
    }

    @Test
    void reservingAnAliasInUseChangesNoLink() {
        final var link = Url.builder().id(41L).shortUrl("Promo").active(true).build();
        when(reservedAliasRepository.save(any(ReservedAlias.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(urlRepository.findByLowerShortUrlIn(List.of("promo"))).thenReturn(List.of(link));

        final var response = reservedAliasService.reserve(new ReservedAliasDto("promo", "Spring campaign"), ADMIN);

        assertThat(response.inUse()).containsExactly(new InUseLink(41L, "Promo", null, true));
        assertThat(link.isActive()).isTrue();
        verify(urlRepository).findByLowerShortUrlIn(List.of("promo"));
        verifyNoMoreInteractions(urlRepository);
    }

    @Test
    void unreserveDeletesACustomEntry() {
        final var promo = reservedAlias(3L, "promo", "Spring campaign");
        when(reservedAliasRepository.findById(3L)).thenReturn(Optional.of(promo));

        reservedAliasService.unreserve(3L, ADMIN);

        verify(reservedAliasRepository).delete(promo);
    }

    @Test
    void aRemovalIsAuditedWithTheRemovedAliasAsTheTargetAndItsReason() {
        when(reservedAliasRepository.findById(2L)).thenReturn(Optional.of(reservedAlias(2L, "blog", "Kept for the company blog")));

        reservedAliasService.unreserve(2L, ADMIN);

        verify(auditLogService).log(ADMIN, AuditAction.UNRESERVE_ALIAS, TARGET_TYPE, "blog", "reason: Kept for the company blog");
        verifyNoMoreInteractions(auditLogService);
        assertThat(auditedInsideATransaction).isEmpty();
    }

    @Test
    void unreservingAnUnknownIdIsNotFound() {
        when(reservedAliasRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservedAliasService.unreserve(99L, ADMIN))
                .isInstanceOf(ReservedAliasNotFoundException.class)
                .hasMessage("Reserved alias not found");

        verify(reservedAliasRepository, never()).delete(any());
        verify(auditLogService).logFailure(ADMIN, AuditAction.UNRESERVE_ALIAS, TARGET_TYPE, "99", "Reserved alias not found");
        verifyNoMoreInteractions(auditLogService);
        assertThat(auditedInsideATransaction).isEmpty();
    }

    private Object recordIfInsideATransaction(final AuditAction action) {
        if (inTransaction) {
            auditedInsideATransaction.add(action.name());
        }
        return null;
    }

    private TransactionOperations recordingTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(final TransactionCallback<T> action) {
                inTransaction = true;
                try {
                    return action.doInTransaction(new SimpleTransactionStatus());
                } finally {
                    inTransaction = false;
                }
            }
        };
    }

    private static ReservedAlias reservedAlias(final long id, final String alias, final String reason) {
        return ReservedAlias.builder().id(id).alias(alias).reason(reason).createdAt(RESERVED_AT).createdBy(ADMIN).build();
    }

}
