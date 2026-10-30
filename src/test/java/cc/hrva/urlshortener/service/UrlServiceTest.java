package cc.hrva.urlshortener.service;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.CreateUrlToUrlConverter;
import cc.hrva.urlshortener.converter.UrlToPeekUrlConverter;
import cc.hrva.urlshortener.converter.UrlUpdateDtoToUrlConverter;
import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.DomainRef;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.DestinationHealth;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.InvalidApiKeyException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.PeekUrl;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CreatedVia;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.burst.CreatedLink;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.domain.DomainRoute;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultSuspiciousUrlService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import cc.hrva.urlshortener.validator.UrlValidator;
import cc.hrva.urlshortener.validator.impl.DefaultUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    private static final String BLOCKLIST_REFUSAL = "This destination is on a blocklist and can't be shortened. If you think that's a mistake, contact us.";
    private static final List<String> YOUNG_DOMAIN = List.of("domain registered in the last 30 days");
    private static final String FRONTEND = "https://hrva.cc";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final ClickRequest CLICK = ClickRequest.of(CLIENT_IP, "UA", "https://t.co/x", "HR");
    private static final String CREATE_LIMIT_REFUSAL = "You've created a lot of links in a short time. Try again in an hour.";
    private static final String BLOCKED_WHILE_EDITING = "This link was blocked while you were editing it. Reload and try again.";
    private static final DomainRoute GO = new DomainRoute(7L, "go.example.com", CustomDomainStatus.ACTIVE, true);
    private static final String DOMAIN_REFUSAL = "Choose one of your active domains, or leave it empty for hrva.cc.";
    private static final String DOMAIN_OFF = "This link's domain isn't active, so the link can't be turned on.";

    private UrlService urlService;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private UserService userService;

    @Mock
    private UrlValidator urlValidator;

    @Mock
    private ApiKeyValidator apiKeyValidator;

    @Mock
    private AppProperties appProperties;

    @Mock
    private UrlUpdateDtoToUrlConverter urlUpdateDtoToUrlConverter;

    @Mock
    private ClickAnalyticsService clickAnalyticsService;

    @Mock
    private CreateUrlToUrlConverter createUrlToUrlConverter;

    @Mock
    private UrlToPeekUrlConverter urlToPeekUrlConverter;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private SafeBrowsingService safeBrowsingService;

    @Mock
    private SuspiciousUrlService suspiciousUrlService;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private LinkCheckService linkCheckService;

    @Mock
    private ClickCheckService clickCheckService;

    @Mock
    private ScammerSignalService scammerSignalService;

    @Mock
    private RedirectChainService redirectChainService;

    @Mock
    private ReservedAliasService reservedAliasService;

    @Mock
    private BurstAlertService burstAlertService;

    @Mock
    private DestinationCheckRepository destinationCheckRepository;

    @Mock
    private CustomDomainRegistry customDomainRegistry;

    @Mock
    private CustomDomainRepository customDomainRepository;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(rateLimiter.tryAcquire(any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(urlValidator.checkIfLongUrlIsNotBlocklisted(any())).thenReturn(LinkVerdict.clean());
        org.mockito.Mockito.lenient().when(clickCheckService.mayRedirect(org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(true);
        this.urlService = new DefaultUrlService(
                userService,
                urlValidator,
                apiKeyService,
                urlRepository,
                appProperties,
                apiKeyValidator,
                clickAnalyticsService,
                urlToPeekUrlConverter,
                createUrlToUrlConverter,
                urlUpdateDtoToUrlConverter,
                new UrlCache(cacheManager),
                objectMapper,
                meterRegistry,
                passwordEncoder,
                suspiciousUrlService,
                rateLimiter,
                reviewQueueService,
                scammerSignalService,
                clickCheckService,
                redirectChainService,
                TransactionOperations.withoutTransaction(),
                reservedAliasService,
                burstAlertService,
                destinationCheckRepository,
                customDomainRegistry,
                customDomainRepository
        );
    }

    @Test
    void updateRefusesCallersWhoAreNeitherOwnerNorAdminBeforeChangingAnything() {
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com").build();
        final var update = UrlUpdateDto.builder().id(7L).password("locked").visitLimit(1L).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        org.mockito.Mockito.doThrow(new NoAuthorizationException("You don't have authorization for this action"))
                .when(urlValidator).verifyUserAdminOrOwner(url);

        assertThatThrownBy(() -> urlService.updateUrl(update))
                .isInstanceOf(NoAuthorizationException.class);

        verify(urlUpdateDtoToUrlConverter, never()).convert(any());
        verify(urlRepository, never()).save(any());
    }

    @Test
    void updateChecksOwnershipThenAppliesTheChanges() {
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).visitLimit(10L).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(url);
        when(urlRepository.save(url)).thenReturn(url);

        urlService.updateUrl(update);

        final var order = org.mockito.Mockito.inOrder(urlValidator, urlUpdateDtoToUrlConverter);
        order.verify(urlValidator).verifyUserAdminOrOwner(url);
        order.verify(urlUpdateDtoToUrlConverter).convert(update);
    }

    @Test
    void changingTheDestinationRequiresAnAdminAndNeverSaves() {
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://new.example").build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl("https://new.example").visits(0L).active(true).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);
        org.mockito.Mockito.doThrow(new NoAuthorizationException("Only admins can change where a link points"))
                .when(urlValidator).verifyUserAdmin();

        assertThatThrownBy(() -> urlService.updateUrl(update))
                .isInstanceOf(NoAuthorizationException.class);

        verify(urlRepository, never()).save(any());
    }

    @Test
    void adminDestinationChangeIsValidatedLikeANewLink() {
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://new.example").build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl("https://new.example").visits(0L).active(true).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);
        when(urlRepository.save(url)).thenReturn(url);

        final var result = urlService.updateUrl(update);

        assertThat(result.longUrl()).isEqualTo("https://new.example");
        final var order = org.mockito.Mockito.inOrder(urlValidator, urlRepository);
        order.verify(urlValidator).verifyUserAdmin();
        order.verify(urlValidator).checkIfLongUrlIsValid(changed);
        order.verify(urlValidator).checkIfLongUrlIsNotAShortener(changed);
        order.verify(urlValidator).checkIfLongUrlIsNotBlocklisted(changed);
        order.verify(urlValidator).checkIfUrlSafe(changed);
        order.verify(urlRepository).save(url);
        verify(reviewQueueService).resolveOpen(url, ReviewResolution.DESTINATION_CHANGED, "system");
        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void editingTheDestinationClearsDeadState() {
        final var deadSince = LocalDateTime.of(2026, 10, 20, 2, 11);
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true)
                .destinationDeadSince(deadSince).destinationLastStatus("404").destinationFailStreak(4).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://new.example").build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl("https://new.example").visits(0L).active(true).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);
        when(urlRepository.save(url)).thenReturn(url);

        final var result = urlService.updateUrl(update);

        final var order = org.mockito.Mockito.inOrder(urlRepository, destinationCheckRepository);
        order.verify(urlRepository).save(url);
        order.verify(destinationCheckRepository).reset(7L);
        assertThat(result.destinationHealth()).isNull();
    }

    @Test
    void keepingTheDestinationKeepsItsDeadState() {
        final var deadSince = LocalDateTime.of(2026, 10, 20, 2, 11);
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://same.example").visits(0L).active(true)
                .destinationDeadSince(deadSince).destinationLastStatus("410").build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://same.example").visitLimit(5L).build();
        final var edited = Url.builder().id(7L).shortUrl("abc").longUrl("https://same.example").visitLimit(5L).visits(0L).active(true).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(edited);
        when(urlRepository.save(url)).thenReturn(url);

        final var result = urlService.updateUrl(update);

        verify(destinationCheckRepository, never()).reset(anyLong());
        assertThat(result.destinationHealth()).isEqualTo(new DestinationHealth(deadSince, "410"));
    }

    @Test
    void theAdminListFiltersDeadDestinationsOnlyWhenAsked() {
        final var pageable = PageRequest.of(0, 20);
        final var search = new UrlSearchDto();
        search.setDeadDestination(true);
        when(urlRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(new PageImpl<>(List.of()));

        urlService.getAllUrls(pageable, search);

        final var captor = ArgumentCaptor.forClass(Specification.class);
        verify(urlRepository).findAll(captor.capture(), eq(pageable));
        final var root = mock(Root.class, RETURNS_DEEP_STUBS);
        final var builder = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        captor.getValue().toPredicate(root, mock(CriteriaQuery.class), builder);
        verify(root).get("destinationDeadSince");
        verify(root).get("active");

        search.setDeadDestination(false);
        when(urlRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of()));
        urlService.getAllUrls(pageable, search);
        verify(urlRepository).findAll(pageable);
    }

    @Test
    void aFlaggedNewDestinationIsSavedAndQueuedForReview() {
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl("https://findfavoritedate.life/?u=f6yp608").visits(0L).active(true).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(changed)).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));
        when(urlRepository.save(url)).thenReturn(url);

        urlService.updateUrl(update);

        final var order = org.mockito.Mockito.inOrder(urlRepository, reviewQueueService);
        order.verify(urlRepository).save(url);
        order.verify(reviewQueueService).resolveOpen(url, ReviewResolution.DESTINATION_CHANGED, "system");
        order.verify(reviewQueueService).flag(url, YOUNG_DOMAIN);
        assertThat(url.getLongUrl()).isEqualTo("https://findfavoritedate.life/?u=f6yp608");
    }

    @Test
    void evenAnAdminCannotPointALinkAtABlocklistedDestination() {
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://njuskaio.cam/a/MAUB3").build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl("https://njuskaio.cam/a/MAUB3").visits(0L).active(true).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(changed)).thenThrow(new UrlValidationException(BLOCKLIST_REFUSAL));

        assertThatThrownBy(() -> urlService.updateUrl(update))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(BLOCKLIST_REFUSAL);

        verify(urlValidator, never()).checkIfUrlSafe(any());
        verify(urlRepository, never()).save(any());
        verify(reviewQueueService, never()).resolveOpen(any(), any(), any());
        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void keepingTheSameDestinationDoesNotNeedAnAdmin() {
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://same.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://same.example").visitLimit(5L).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(url);
        when(urlRepository.save(url)).thenReturn(url);

        urlService.updateUrl(update);

        verify(urlValidator, never()).verifyUserAdmin();
        verify(urlValidator, never()).checkIfLongUrlIsNotBlocklisted(any());
        verify(urlValidator, never()).checkIfUrlSafe(any());
        verify(reviewQueueService, never()).resolveOpen(any(), any(), any());
        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void aBlockThatLandsWhileAnEditIsCheckedIsNeverUndone() {
        final var loaded = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://example.com/menu").build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/menu").visits(0L).active(true).build();
        final var blockedMeanwhile = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(false)
                .blockReason(BlockReason.DOMAIN_RULE).blockDetail("old.example").blockedAt(LocalDateTime.of(2026, 10, 22, 9, 0)).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(loaded), Optional.of(blockedMeanwhile));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);

        assertThatThrownBy(() -> urlService.updateUrl(update))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(BLOCKED_WHILE_EDITING);

        verify(urlRepository, never()).save(any());
        verify(reviewQueueService, never()).resolveOpen(any(), any(), any());
        verify(reviewQueueService, never()).flag(any(), any());
        verify(redirectChainService, never()).checkLater(org.mockito.ArgumentMatchers.anyLong());
        assertThat(blockedMeanwhile.getLongUrl()).isEqualTo("https://old.example");
        assertThat(blockedMeanwhile.isActive()).isFalse();
    }

    @Test
    void anEditLandsOnTheLinkAsItIsNowAndNotOnTheCopyLoadedBeforeTheChecks() {
        final var loaded = Url.builder().id(7L).shortUrl("abc").longUrl("https://same.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).visitLimit(10L).tags(Set.of("work")).password("s3cret").build();
        final var edited = Url.builder().id(7L).shortUrl("abc").longUrl("https://same.example").visits(0L).active(true)
                .visitLimit(10L).tags(Set.of("work")).passwordHash("hash").build();
        final var current = Url.builder().id(7L).shortUrl("abc").longUrl("https://same.example").visits(3L).active(false).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(loaded), Optional.of(current));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(edited);
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));

        urlService.updateUrl(update);

        final var order = org.mockito.Mockito.inOrder(urlRepository);
        order.verify(urlRepository, times(2)).findById(7L);
        order.verify(urlRepository).save(current);
        assertThat(current)
                .extracting(Url::getVisitLimit, Url::getTags, Url::getPasswordHash, Url::isActive, Url::getVisits)
                .containsExactly(10L, Set.of("work"), "hash", false, 3L);
    }

    @Test
    void anEditOfALinkThatWasAlreadyBlockedKeepsItsBlock() {
        final var blockedAt = LocalDateTime.of(2026, 10, 22, 9, 0);
        final var loaded = Url.builder().id(7L).shortUrl("abc").longUrl("https://same.example").visits(0L).active(false)
                .blockReason(BlockReason.ADMIN).blockedAt(blockedAt).build();
        final var current = Url.builder().id(7L).shortUrl("abc").longUrl("https://same.example").visits(0L).active(false)
                .blockReason(BlockReason.ADMIN).blockedAt(blockedAt).build();
        final var update = UrlUpdateDto.builder().id(7L).tags(Set.of("work")).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(loaded), Optional.of(current));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenAnswer(invocation -> {
            loaded.setTags(Set.of("work"));
            return loaded;
        });
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));

        urlService.updateUrl(update);

        verify(urlRepository).save(current);
        assertThat(current)
                .extracting(Url::getTags, Url::isActive, Url::getBlockReason, Url::getBlockedAt)
                .containsExactly(Set.of("work"), false, BlockReason.ADMIN, blockedAt);
    }

    @Test
    void aBlockThatLandsWhileALinkIsReactivatedIsNeverUndone() {
        final var loaded = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/menu").visits(0L).active(false).build();
        final var blockedMeanwhile = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/menu").visits(0L).active(false)
                .threatType(cc.hrva.urlshortener.model.enums.ThreatType.MALWARE).blockReason(BlockReason.SAFE_BROWSING)
                .blockDetail("MALWARE").blockedAt(LocalDateTime.of(2026, 10, 22, 4, 31)).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(loaded), Optional.of(blockedMeanwhile));

        assertThatThrownBy(() -> urlService.activateUrl(7L))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(BLOCKED_WHILE_EDITING);

        verify(urlRepository, never()).save(any());
        assertThat(blockedMeanwhile)
                .extracting(Url::isActive, Url::getBlockReason, Url::getThreatType)
                .containsExactly(false, BlockReason.SAFE_BROWSING, cc.hrva.urlshortener.model.enums.ThreatType.MALWARE);
    }

    @Test
    void anAdminCanStillReactivateALinkWhoseBlockIsUnchangedSinceTheChecks() {
        final var blockedAt = LocalDateTime.of(2026, 10, 20, 2, 0);
        final var loaded = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/menu").visits(0L).active(false)
                .blockReason(BlockReason.BLOCKLIST).blockDetail("HaGeZi TIF medium").blockedAt(blockedAt).build();
        final var current = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/menu").visits(0L).active(false)
                .blockReason(BlockReason.BLOCKLIST).blockDetail("HaGeZi TIF medium").blockedAt(blockedAt).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(loaded), Optional.of(current));
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));

        final var result = urlService.activateUrl(7L);

        verify(urlRepository).save(current);
        assertThat(result.active()).isTrue();
        assertThat(current)
                .extracting(Url::isActive, Url::getBlockReason, Url::getBlockDetail, Url::getBlockedAt)
                .containsExactly(true, null, null, null);
    }

    @Test
    void expiredLinksAreSwitchedOffByIdWithoutWritingBackLoadedCopies() {
        when(urlRepository.findExpiredActiveIds(any(LocalDateTime.class))).thenReturn(List.of(3L, 4L));
        when(urlRepository.deactivateIfActive(List.of(3L, 4L))).thenReturn(2);

        urlService.deactivateExpiredUrls();

        verify(urlRepository).deactivateIfActive(List.of(3L, 4L));
        verify(urlRepository, never()).save(any());
        verify(urlRepository, never()).saveAll(any());
    }

    @Test
    void nothingIsUpdatedWhenNoLinkHasExpired() {
        when(urlRepository.findExpiredActiveIds(any(LocalDateTime.class))).thenReturn(List.of());

        urlService.deactivateExpiredUrls();

        verify(urlRepository, never()).deactivateIfActive(any());
    }

    @Test
    void createChecksTheDestinationAfterTheShortenerRefusalAndBeforeSafeBrowsing() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        final var url = Url.builder().longUrl("https://example.com/menu").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(persisted(url));

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        final var order = org.mockito.Mockito.inOrder(urlValidator, urlRepository);
        order.verify(urlValidator).longUrlInUrl(url);
        order.verify(urlValidator).checkIfLongUrlIsValid(url);
        order.verify(urlValidator).checkIfLongUrlIsNotAShortener(url);
        order.verify(urlValidator).checkIfLongUrlIsNotBlocklisted(url);
        order.verify(urlValidator).checkIfUrlSafe(url);
        order.verify(urlValidator).checkIfUrlExpirationDateIsInThePast(url);
        order.verify(urlRepository).save(url);
        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void anOverlongDestinationNeverReachesTheLinkChecks() {
        final var overlong = "https://example.com/" + "a".repeat(UrlValidator.MAX_LONG_URL_LENGTH);
        final var createUrlDto = CreateUrlDto.builder().longUrl(overlong).build();
        final var created = Url.builder().longUrl(overlong).build();
        final var existing = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl(overlong).build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl(overlong).visits(0L).active(true).build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(created);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(urlRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);
        org.mockito.Mockito.doThrow(new UrlValidationException(UrlValidator.LONG_URL_TOO_LONG))
                .when(urlValidator).checkIfLongUrlIsValid(any());

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(UrlValidator.LONG_URL_TOO_LONG);
        assertThatThrownBy(() -> urlService.updateUrl(update))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(UrlValidator.LONG_URL_TOO_LONG);

        verify(urlValidator).checkIfLongUrlIsValid(created);
        verify(urlValidator).checkIfLongUrlIsValid(changed);
        verify(urlValidator, never()).checkIfLongUrlIsNotAShortener(any());
        verify(urlValidator, never()).checkIfLongUrlIsNotBlocklisted(any());
        verify(urlValidator, never()).checkIfUrlSafe(any());
        verify(urlRepository, never()).save(any());
    }

    @Test
    void aFlaggedLinkIsSavedAndQueuedForReview() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var url = Url.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var saved = Url.builder().id(9L).longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(saved);

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        verify(reviewQueueService).flag(saved, YOUNG_DOMAIN);
    }

    @Test
    void aFlaggedAnonymousLinkIsSavedAndQueuedForReview() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var url = Url.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(persisted(url));

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        final var order = org.mockito.Mockito.inOrder(urlRepository, reviewQueueService);
        order.verify(urlRepository).save(url);
        order.verify(reviewQueueService).flag(url, YOUNG_DOMAIN);
    }

    @Test
    void aFlaggedLinkMadeWithAnApiKeyIsSavedAndQueuedForReview() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var url = Url.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var apiKey = ApiKey.builder().owner(User.builder().id(1L).build()).build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(apiKey);
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(persisted(url));

        urlService.saveUrlWithApiKey(createUrlDto, "apikey", CreatedVia.API_KEY);

        final var order = org.mockito.Mockito.inOrder(urlValidator, urlRepository, reviewQueueService);
        order.verify(urlValidator).checkIfLongUrlIsNotAShortener(url);
        order.verify(urlValidator).checkIfLongUrlIsNotBlocklisted(url);
        order.verify(urlValidator).checkIfUrlSafe(url);
        order.verify(urlRepository).save(url);
        order.verify(reviewQueueService).flag(url, YOUNG_DOMAIN);
    }

    @Test
    void anAllowedLinkSkipsTheReviewButStillGoesThroughSafeBrowsing() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://github.com/HrvaLabs").build();
        final var url = Url.builder().longUrl("https://github.com/HrvaLabs").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(url))
                .thenReturn(new LinkVerdict(Outcome.ALLOWED, List.of("allowed domain github.com"), null, null));
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(persisted(url));

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        verify(urlValidator).checkIfUrlSafe(url);
        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void aBlocklistedDestinationIsRefusedBeforeSafeBrowsingAndNeverSaved() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        when(appProperties.isAnonymousUrlCreationEnabled()).thenReturn(true);
        when(linkCheckService.check("https://njuskaio.cam/a/MAUB3")).thenReturn(blocked());

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(BLOCKLIST_REFUSAL);

        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(urlRepository, never()).save(any(Url.class));
        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void aBlocklistedDestinationSentWithAnApiKeyIsRefusedWithoutUsingTheKey() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        when(linkCheckService.check("https://njuskaio.cam/a/MAUB3")).thenReturn(blocked());
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(User.builder().id(1L).build()).build());

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "apikey", CreatedVia.API_KEY))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(BLOCKLIST_REFUSAL);

        verify(apiKeyService, never()).recordUse(any());
        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void theLinkCheckRunsBeforeTheTransactionThatSavesASignedInUsersLink() {
        final var checkedInTransaction = new AtomicReference<Boolean>();
        final var savedInTransaction = new AtomicReference<Boolean>();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        recordTransactionDuringCheck("https://example.com/menu", checkedInTransaction);
        recordTransactionDuringSave(savedInTransaction);

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(checkedInTransaction).hasValue(false);
        assertThat(savedInTransaction).hasValue(true);
    }

    @Test
    void theLinkCheckRunsBeforeTheTransactionThatSavesAnAnonymousLink() {
        final var checkedInTransaction = new AtomicReference<Boolean>();
        final var savedInTransaction = new AtomicReference<Boolean>();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(appProperties.isAnonymousUrlCreationEnabled()).thenReturn(true);
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        recordTransactionDuringCheck("https://example.com/menu", checkedInTransaction);
        recordTransactionDuringSave(savedInTransaction);

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(checkedInTransaction).hasValue(false);
        assertThat(savedInTransaction).hasValue(true);
    }

    @Test
    void theLinkCheckRunsBeforeTheTransactionThatSavesALinkMadeWithAnApiKey() {
        final var checkedInTransaction = new AtomicReference<Boolean>();
        final var savedInTransaction = new AtomicReference<Boolean>();
        final var keyUsedInTransaction = new AtomicReference<Boolean>();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(User.builder().id(1L).build()).build());
        org.mockito.Mockito.doAnswer(invocation -> {
            keyUsedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(apiKeyService).recordUse(any());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        recordTransactionDuringCheck("https://example.com/menu", checkedInTransaction);
        recordTransactionDuringSave(savedInTransaction);

        transactionalUrlService().saveUrlWithApiKey(createUrlDto, "apikey", CreatedVia.API_KEY);

        assertThat(checkedInTransaction).hasValue(false);
        assertThat(savedInTransaction).hasValue(true);
        assertThat(keyUsedInTransaction).hasValue(true);
    }

    @Test
    void theReviewIsQueuedInsideTheTransactionThatSavesTheLink() {
        final var queuedInTransaction = new AtomicReference<Boolean>();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(linkCheckService.check("https://findfavoritedate.life/?u=f6yp608")).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> persisted(invocation.getArgument(0)));
        org.mockito.Mockito.doAnswer(invocation -> {
            queuedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(reviewQueueService).flag(any(), any());

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(queuedInTransaction).hasValue(true);
    }

    @Test
    void theOldReviewIsClosedAndTheNewOneQueuedInsideTheTransactionThatSavesANewDestination() {
        final var resolvedInTransaction = new AtomicReference<Boolean>();
        final var queuedInTransaction = new AtomicReference<Boolean>();
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl("https://findfavoritedate.life/?u=f6yp608").visits(0L).active(true).build();
        when(userService.getUserFromToken()).thenReturn(admin());
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);
        when(linkCheckService.check("https://findfavoritedate.life/?u=f6yp608")).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doAnswer(invocation -> {
            resolvedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(reviewQueueService).resolveOpen(any(), any(), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            queuedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(reviewQueueService).flag(any(), any());

        transactionalUrlService().updateUrl(update);

        assertThat(resolvedInTransaction).hasValue(true);
        assertThat(queuedInTransaction).hasValue(true);
    }

    @Test
    void aSignedInUsersNewLinkHasItsRedirectsCheckedInTheBackgroundOnceItIsCommitted() {
        final var checkedInTransaction = new AtomicReference<Boolean>();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> persisted(invocation.getArgument(0)));
        when(linkCheckService.check("https://example.com/menu")).thenReturn(LinkVerdict.clean());
        recordTransactionDuringChainCheck(1L, checkedInTransaction);

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(checkedInTransaction).hasValue(false);
    }

    @Test
    void anAnonymousLinkHasItsRedirectsCheckedInTheBackgroundOnceItIsCommitted() {
        final var checkedInTransaction = new AtomicReference<Boolean>();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(appProperties.isAnonymousUrlCreationEnabled()).thenReturn(true);
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> persisted(invocation.getArgument(0)));
        when(linkCheckService.check("https://example.com/menu")).thenReturn(LinkVerdict.clean());
        recordTransactionDuringChainCheck(1L, checkedInTransaction);

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(checkedInTransaction).hasValue(false);
    }

    @Test
    void aLinkMadeWithAnApiKeyHasItsRedirectsCheckedInTheBackgroundOnceItIsCommitted() {
        final var checkedInTransaction = new AtomicReference<Boolean>();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(User.builder().id(1L).build()).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> persisted(invocation.getArgument(0)));
        when(linkCheckService.check("https://example.com/menu")).thenReturn(LinkVerdict.clean());
        recordTransactionDuringChainCheck(1L, checkedInTransaction);

        transactionalUrlService().saveUrlWithApiKey(createUrlDto, "apikey", CreatedVia.API_KEY);

        assertThat(checkedInTransaction).hasValue(false);
    }

    @Test
    void aNewDestinationForgetsTheOldChainAndHasItsRedirectsCheckedOnceItIsCommitted() {
        final var checkedInTransaction = new AtomicReference<Boolean>();
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true)
                .finalUrl("https://old-landing.example/").chainCheckedAt(LocalDateTime.of(2026, 10, 20, 4, 30)).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://example.com/menu").build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/menu").visits(0L).active(true)
                .finalUrl("https://old-landing.example/").chainCheckedAt(LocalDateTime.of(2026, 10, 20, 4, 30)).build();
        when(userService.getUserFromToken()).thenReturn(admin());
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(linkCheckService.check("https://example.com/menu")).thenReturn(LinkVerdict.clean());
        recordTransactionDuringChainCheck(7L, checkedInTransaction);

        transactionalUrlService().updateUrl(update);

        assertThat(checkedInTransaction).hasValue(false);
        assertThat(url.getLongUrl()).isEqualTo("https://example.com/menu");
        assertThat(url.getFinalUrl()).isNull();
        assertThat(url.getChainCheckedAt()).isNull();
    }

    @Test
    void keepingTheDestinationKeepsTheChainAndChecksNoRedirects() {
        final var checkedAt = LocalDateTime.of(2026, 10, 20, 4, 30);
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://same.example").visits(0L).active(true)
                .finalUrl("https://landing.example/").chainCheckedAt(checkedAt).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://same.example").visitLimit(5L).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(url);
        when(urlRepository.save(url)).thenReturn(url);

        urlService.updateUrl(update);

        verify(redirectChainService, never()).checkLater(org.mockito.ArgumentMatchers.anyLong());
        assertThat(url.getFinalUrl()).isEqualTo("https://landing.example/");
        assertThat(url.getChainCheckedAt()).isEqualTo(checkedAt);
    }

    @Test
    void aRedirectCheckThatCannotBeScheduledNeverFailsTheCommittedLink() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        final var url = Url.builder().longUrl("https://example.com/menu").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(persisted(url));
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("executor is shutting down"))
                .when(redirectChainService).checkLater(1L);

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(result.id()).isEqualTo(1L);
    }

    @Test
    void aRefusedLinkHasNoRedirectsChecked() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        final var url = Url.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).thenThrow(new UrlValidationException(BLOCKLIST_REFUSAL));

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB)).isInstanceOf(UrlValidationException.class);

        verify(redirectChainService, never()).checkLater(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void aSignedInLinkIsReportedToTheBurstCheck() {
        stubLinkCreation();
        when(userService.getUserFromToken()).thenReturn(User.builder().id(7L).build());

        urlService.saveUrlRouting(link(0), CLIENT_IP, CreatedVia.WEB);

        verify(burstAlertService).linkCreated(new CreatedLink(1L, "https://example.com/0", "user:7", Outcome.CLEAN));
    }

    @Test
    void aSignedInLinkWithoutAClientIpIsReportedAsItsAccount() {
        stubLinkCreation();
        when(userService.getUserFromToken()).thenReturn(User.builder().id(7L).build());

        urlService.saveUrlRouting(link(0), null, CreatedVia.MCP);

        verify(burstAlertService).linkCreated(new CreatedLink(1L, "https://example.com/0", "user:7", Outcome.CLEAN));
    }

    @Test
    void anAnonymousLinkIsReportedWithItsHashedNetwork() {
        stubLinkCreation();

        urlService.saveUrlRouting(link(0), CLIENT_IP, CreatedVia.WEB);

        final var reported = org.mockito.ArgumentCaptor.forClass(CreatedLink.class);
        verify(burstAlertService).linkCreated(reported.capture());
        assertThat(reported.getValue().creator()).startsWith("anonymous:").doesNotContain(CLIENT_IP)
                .isEqualTo(RateLimiter.clientKey("anonymous", CLIENT_IP));
        assertThat(reported.getValue()).isEqualTo(new CreatedLink(1L, "https://example.com/0", RateLimiter.clientKey("anonymous", CLIENT_IP), Outcome.CLEAN));
    }

    @Test
    void aReusedAnonymousLinkIsReportedWithItsOwnId() {
        final var createUrlDto = link(0);
        final var existing = Url.builder().id(12L).shortUrl("v0DB3").longUrl("https://example.com/0").active(true).build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/0").build());
        when(urlRepository.existsUrlByLongUrlAndActiveTrueAndOwnerIsNull("https://example.com/0")).thenReturn(true);
        when(urlRepository.findByLongUrlAndActiveTrue("https://example.com/0")).thenReturn(Optional.of(existing));

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        verify(burstAlertService).linkCreated(new CreatedLink(12L, "https://example.com/0", RateLimiter.clientKey("anonymous", CLIENT_IP), Outcome.CLEAN));
    }

    @Test
    void anApiKeyLinkIsReportedAsItsOwner() {
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(User.builder().id(9L).build()).build());
        stubLinkCreation();

        urlService.saveUrlWithApiKey(link(0), "apikey", CreatedVia.API_KEY);

        verify(burstAlertService).linkCreated(new CreatedLink(1L, "https://example.com/0", "user:9", Outcome.CLEAN));
    }

    @Test
    void theVerdictTravelsWithTheLink() {
        stubLinkCreation();
        when(userService.getUserFromToken()).thenReturn(User.builder().id(7L).build());
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(any())).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));

        urlService.saveUrlRouting(link(0), CLIENT_IP, CreatedVia.WEB);

        verify(burstAlertService).linkCreated(new CreatedLink(1L, "https://example.com/0", "user:7", Outcome.FLAG));
    }

    @Test
    void eachLinkOfABulkRequestIsReported() {
        stubLinkCreation();
        when(userService.getUserFromToken()).thenReturn(User.builder().id(7L).build());

        List.of(link(0), link(1), link(2)).forEach(dto -> urlService.saveUrlRouting(dto, CLIENT_IP, CreatedVia.BULK));

        final var reported = org.mockito.ArgumentCaptor.forClass(CreatedLink.class);
        verify(burstAlertService, times(3)).linkCreated(reported.capture());
        assertThat(reported.getAllValues()).extracting(CreatedLink::longUrl)
                .containsExactly("https://example.com/0", "https://example.com/1", "https://example.com/2");
    }

    @Test
    void aRefusedLinkIsNeverReportedToTheBurstCheck() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        final var url = Url.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).thenThrow(new UrlValidationException(BLOCKLIST_REFUSAL));

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB)).isInstanceOf(UrlValidationException.class);

        verifyNoInteractions(burstAlertService);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailingBurstCheckNeverFailsTheCreate(final CapturedOutput output) {
        stubLinkCreation();
        when(userService.getUserFromToken()).thenReturn(User.builder().id(7L).build());
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("executor is shutting down"))
                .when(burstAlertService).linkCreated(any());

        final var result = urlService.saveUrlRouting(link(0), CLIENT_IP, CreatedVia.WEB);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(output).containsOnlyOnce("Could not schedule the burst check urlId=1 exception=org.springframework.core.task.TaskRejectedException");
    }

    @Test
    void aFailingBurstCheckNeverFailsAnApiKeyCreate() {
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(User.builder().id(9L).build()).build());
        stubLinkCreation();
        org.mockito.Mockito.doThrow(new IllegalStateException("unexpected")).when(burstAlertService).linkCreated(any());

        final var result = urlService.saveUrlWithApiKey(link(0), "apikey", CreatedVia.API_KEY);

        assertThat(result.id()).isEqualTo(1L);
    }

    @Test
    void aSignedInUsersNewLinkIsReportedToTheBurstCheckOnceItIsCommitted() {
        final var reportedInTransaction = new AtomicReference<Boolean>();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> persisted(invocation.getArgument(0)));
        when(linkCheckService.check("https://example.com/menu")).thenReturn(LinkVerdict.clean());
        recordTransactionDuringBurstCheck(reportedInTransaction);

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(reportedInTransaction).hasValue(false);
    }

    @Test
    void aLinkMadeWithAnApiKeyIsReportedToTheBurstCheckOnceItIsCommitted() {
        final var reportedInTransaction = new AtomicReference<Boolean>();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(User.builder().id(1L).build()).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> persisted(invocation.getArgument(0)));
        when(linkCheckService.check("https://example.com/menu")).thenReturn(LinkVerdict.clean());
        recordTransactionDuringBurstCheck(reportedInTransaction);

        transactionalUrlService().saveUrlWithApiKey(createUrlDto, "apikey", CreatedVia.API_KEY);

        assertThat(reportedInTransaction).hasValue(false);
    }

    @Test
    void anUnknownApiKeyIsRefusedBeforeTheSlowLinkCheck() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(apiKeyService.fetchApiKeyByKey("junk")).thenThrow(new ApiKeyNotFoundException("Sent API key doesn't exist"));

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "junk", CreatedVia.API_KEY))
                .isInstanceOf(ApiKeyNotFoundException.class);

        verify(linkCheckService, never()).check(anyString());
        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void anInvalidApiKeyIsRefusedBeforeTheSlowLinkCheck() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        org.mockito.Mockito.doThrow(new InvalidApiKeyException("API key has expired")).when(apiKeyValidator).apiKeyExistsByKeyAndIsValid("expired");

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "expired", CreatedVia.API_KEY))
                .isInstanceOf(InvalidApiKeyException.class)
                .hasMessage("API key has expired");

        verify(linkCheckService, never()).check(anyString());
        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(apiKeyService, never()).recordUse(any());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void anonymousCreationWhileItIsSwitchedOffIsRefusedBeforeTheSlowLinkCheck() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(ApiException.class)
                .hasMessage("Sign in to create short URLs");

        verify(linkCheckService, never()).check(anyString());
        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void anApiKeyCreateOverTheRateLimitIsRefusedBeforeTheSlowLinkCheck() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(User.builder().id(1L).build()).build());
        when(rateLimiter.tryAcquire(eq("create:user:1"), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "apikey", CreatedVia.API_KEY))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(CREATE_LIMIT_REFUSAL);

        verify(linkCheckService, never()).check(anyString());
        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(apiKeyService, never()).recordUse(any());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void anAnonymousCreateOverTheRateLimitIsRefusedBeforeTheSlowLinkCheck() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(rateLimiter.tryAcquire(startsWith("create:anon"), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(CREATE_LIMIT_REFUSAL);

        verify(linkCheckService, never()).check(anyString());
        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void aFlaggedDestinationThatReusesAnAnonymousLinkQueuesThatLink() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var url = Url.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var existing = Url.builder().id(12L).shortUrl("v0DB3").longUrl("https://findfavoritedate.life/?u=f6yp608").active(true).build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));
        when(urlRepository.existsUrlByLongUrlAndActiveTrueAndOwnerIsNull(url.getLongUrl())).thenReturn(true);
        when(urlRepository.findByLongUrlAndActiveTrue(url.getLongUrl())).thenReturn(Optional.of(existing));

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(result.shortUrl()).isEqualTo("v0DB3");
        verify(reviewQueueService).flag(existing, YOUNG_DOMAIN);
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void theLinkCheckRunsBeforeTheTransactionThatSavesANewDestination() {
        final var checkedInTransaction = new AtomicReference<Boolean>();
        final var savedInTransaction = new AtomicReference<Boolean>();
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://example.com/menu").build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/menu").visits(0L).active(true).build();
        when(userService.getUserFromToken()).thenReturn(admin());
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);
        recordTransactionDuringCheck("https://example.com/menu", checkedInTransaction);
        recordTransactionDuringSave(savedInTransaction);

        transactionalUrlService().updateUrl(update);

        assertThat(checkedInTransaction).hasValue(false);
        assertThat(savedInTransaction).hasValue(true);
    }

    @Test
    void theLinkCheckRunsBeforeTheTransactionThatReactivatesALink() {
        final var checkedInTransaction = new AtomicReference<Boolean>();
        final var savedInTransaction = new AtomicReference<Boolean>();
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/menu").visits(0L)
                .blockReason(BlockReason.BLOCKLIST).blockDetail("HaGeZi TIF medium").active(false).build();
        when(userService.getUserFromToken()).thenReturn(admin());
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        recordTransactionDuringCheck("https://example.com/menu", checkedInTransaction);
        recordTransactionDuringSave(savedInTransaction);

        final var result = transactionalUrlService().activateUrl(7L);

        assertThat(checkedInTransaction).hasValue(false);
        assertThat(savedInTransaction).hasValue(true);
        assertThat(result.active()).isTrue();
        assertThat(url.getBlockReason()).isNull();
    }

    @Test
    void shouldSaveUrlRandomShortUrl() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("long").build();
        final var url = Url.builder().longUrl("long").build();

        when(appProperties.getShortUrlLength()).thenReturn(1L);
        when(urlRepository.save(url)).thenReturn(persisted(url));
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);
        assertThat(result.longUrl()).isEqualTo("long");

        verify(reservedAliasService).isReserved(url.getShortUrl());
        verify(urlRepository).aliasTaken(null, url.getShortUrl());
        verify(urlValidator).longUrlInUrl(url);
    }

    @Test
    void generatedAliasSkipsReservedWords() {
        final var url = signedInCreate();
        when(reservedAliasService.isReserved(anyString())).thenReturn(true, false);

        urlService.saveUrlRouting(CreateUrlDto.builder().longUrl("https://example.com/menu").build(), CLIENT_IP, CreatedVia.WEB);

        final var candidates = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reservedAliasService, times(2)).isReserved(candidates.capture());
        assertThat(url.getShortUrl()).hasSize(5).isEqualTo(candidates.getAllValues().getLast());
        verify(urlRepository).aliasTaken(null, url.getShortUrl());
        verify(urlRepository).save(url);
    }

    @Test
    void generatedAliasSkipsTakenOnes() {
        final var url = signedInCreate();
        when(urlRepository.aliasTaken(isNull(), anyString())).thenReturn(true, false);

        urlService.saveUrlRouting(CreateUrlDto.builder().longUrl("https://example.com/menu").build(), CLIENT_IP, CreatedVia.WEB);

        final var candidates = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(urlRepository, times(2)).aliasTaken(isNull(), candidates.capture());
        assertThat(url.getShortUrl()).hasSize(5).isEqualTo(candidates.getAllValues().getLast());
        verify(reservedAliasService, times(2)).isReserved(anyString());
        verify(urlRepository).save(url);
    }

    @Test
    void givesUpAfterTenUnusableCandidates() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/menu").build());
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(reservedAliasService.isReserved(anyString())).thenReturn(true);

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("Couldn't pick a free short URL. Try again.");

        verify(reservedAliasService, times(10)).isReserved(anyString());
        verify(urlRepository, never()).aliasTaken(isNull(), anyString());
        verify(urlRepository, never()).save(any());
    }

    @Test
    void anonymousGeneratedAliasSkipsReservedWords() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        final var url = Url.builder().longUrl("https://example.com/menu").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(null);
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(reservedAliasService.isReserved(anyString())).thenReturn(true, false);
        when(urlRepository.save(url)).thenReturn(persisted(url));

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        final var candidates = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reservedAliasService, times(2)).isReserved(candidates.capture());
        assertThat(url.getShortUrl()).hasSize(5).isEqualTo(candidates.getAllValues().getLast());
        assertThat(url.getOwner()).isNull();
        verify(urlRepository).save(url);
    }

    @Test
    void customAliasStillGoesThroughTheReservedCheck() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").shortUrl("promo").build();
        final var url = Url.builder().longUrl("https://example.com/menu").shortUrl("promo").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(urlRepository.save(url)).thenReturn(persisted(url));

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        final var order = org.mockito.Mockito.inOrder(urlValidator, urlRepository);
        order.verify(urlValidator).checkIfShortUrlIsUnique(null, "promo");
        order.verify(urlValidator).checkIfShortUrlIsReserved("promo");
        order.verify(urlRepository).save(url);
        assertThat(url.getShortUrl()).isEqualTo("promo");
        verifyNoInteractions(reservedAliasService);
    }

    @Test
    void aCustomAliasLostToAConcurrentCreateIsTakenAndNeverCached() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").shortUrl("promo").build();
        final var url = Url.builder().longUrl("https://example.com/menu").shortUrl("promo").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(urlRepository.save(url)).thenReturn(persisted(url));
        org.mockito.Mockito.doThrow(shortUrlTakenMeanwhile()).when(urlRepository).flush();

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("This short URL is already taken. Try another.");

        verify(cacheManager, never()).getCache(any());
        verify(redirectChainService, never()).checkLater(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void aGeneratedAliasLostToAConcurrentCreateIsTheNoFreeAliasRefusalAndNeverCached() {
        final var url = signedInCreate();
        org.mockito.Mockito.doThrow(shortUrlTakenMeanwhile()).when(urlRepository).flush();

        assertThatThrownBy(() -> urlService.saveUrlRouting(CreateUrlDto.builder().longUrl("https://example.com/menu").build(), CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("Couldn't pick a free short URL. Try again.");

        verify(urlRepository).save(url);
        verify(cacheManager, never()).getCache(any());
    }

    @Test
    void anAnonymousGeneratedAliasLostToAConcurrentCreateIsTheNoFreeAliasRefusalAndNeverCached() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        final var url = Url.builder().longUrl("https://example.com/menu").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(null);
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(persisted(url));
        org.mockito.Mockito.doThrow(shortUrlTakenMeanwhile()).when(urlRepository).flush();

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("Couldn't pick a free short URL. Try again.");

        verify(cacheManager, never()).getCache(any());
    }

    @Test
    void anotherIntegrityViolationOnCreateIsNotReportedAsATakenAlias() {
        final var tooLong = new DataIntegrityViolationException("could not execute statement",
                new DataException("Value too long for column \"LONG_URL CHARACTER VARYING(255)\"", new SQLException("Value too long", "22001")));
        signedInCreate();
        org.mockito.Mockito.doThrow(tooLong).when(urlRepository).flush();

        assertThatThrownBy(() -> urlService.saveUrlRouting(CreateUrlDto.builder().longUrl("https://example.com/menu").build(), CLIENT_IP, CreatedVia.WEB))
                .isSameAs(tooLong);
    }

    @Test
    void aLinkIsCreatedOnTheCallersDomain() {
        final var url = signedInCreate();
        final var domain = goExampleCom(CustomDomainStatus.ACTIVE);
        final var cache = new org.springframework.cache.concurrent.ConcurrentMapCache("urls");
        when(cacheManager.getCache("urls")).thenReturn(cache);
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.of(domain));

        final var created = urlService.saveUrlRouting(onTheDomain(" Go.Example.com. "), CLIENT_IP, CreatedVia.WEB);

        assertThat(created.domain()).isEqualTo(new DomainRef(7L, "go.example.com"));
        assertThat(url.getDomain()).isSameAs(domain);
        assertThat(cache.get("7/" + url.getShortUrl())).isNotNull();
        assertThat(cache.get(url.getShortUrl())).isNull();
    }

    @Test
    void aBlankDomainMeansHrvaAndLooksNothingUp() {
        final var url = signedInCreate();

        final var created = urlService.saveUrlRouting(onTheDomain("  "), CLIENT_IP, CreatedVia.WEB);

        assertThat(created.domain()).isNull();
        assertThat(url.getDomain()).isNull();
        verifyNoInteractions(customDomainRepository);
    }

    @Test
    void someoneElsesOrAPendingDomainIsRefused() {
        final var createUrlDto = onTheDomain("go.example.com");
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/menu").build());
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        exception -> assertThat(exception.getFieldErrors()).containsExactly(entry("domain", DOMAIN_REFUSAL)));

        verify(urlRepository, never()).save(any());
        verifyNoInteractions(reservedAliasService);
    }

    @Test
    void anonymousCannotPickADomain() {
        final var createUrlDto = onTheDomain("go.example.com");
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/menu").build());
        when(userService.getUserFromToken()).thenReturn(null);

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        exception -> assertThat(exception.getFieldErrors()).containsExactly(entry("domain", DOMAIN_REFUSAL)));

        verifyNoInteractions(customDomainRepository);
        verify(urlValidator, never()).checkIfAnonymousUrlCreationEnabled();
        verify(urlValidator, never()).checkIfLongUrlIsNotBlocklisted(any());
        verify(urlRepository, never()).save(any());
    }

    @Test
    void theDomainIsLookedUpForTheSignedInAccountSoTheQueryDecidesWhetherItIsUsable() {
        final var createUrlDto = onTheDomain("go.example.com");
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/menu").build());
        when(userService.getUserFromToken()).thenReturn(User.builder().id(42L).active(false).build());

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(FieldValidationException.class);

        verify(customDomainRepository).findUsable("go.example.com", 42L);
    }

    @Test
    void apiKeyCreatesUseTheKeyOwnersDomains() {
        final var createUrlDto = onTheDomain("go.example.com");
        final var url = Url.builder().longUrl("https://example.com/menu").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(apiKeyService.fetchApiKeyByKey("k")).thenReturn(ApiKey.builder().id(3L).owner(User.builder().id(5L).build()).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(customDomainRepository.findUsable("go.example.com", 5L)).thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)));
        when(urlRepository.save(url)).thenReturn(persisted(url));

        final var created = urlService.saveUrlWithApiKey(createUrlDto, "k", CreatedVia.API_KEY);

        assertThat(created.domain()).isEqualTo(new DomainRef(7L, "go.example.com"));
        verify(userService, never()).getUserFromToken();
        verify(apiKeyService).recordUse(3L);
    }

    @Test
    void anApiKeyCreateOnADomainTheOwnerCantUseNeitherSavesNorCountsTheCall() {
        final var createUrlDto = onTheDomain("go.example.com");
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/menu").build());
        when(apiKeyService.fetchApiKeyByKey("k")).thenReturn(ApiKey.builder().id(3L).owner(User.builder().id(5L).build()).build());
        when(customDomainRepository.findUsable("go.example.com", 5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.saveUrlWithApiKey(createUrlDto, "k", CreatedVia.API_KEY))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        exception -> assertThat(exception.getFieldErrors()).containsExactly(entry("domain", DOMAIN_REFUSAL)));

        verify(apiKeyService, never()).recordUse(anyLong());
        verify(urlRepository, never()).save(any());
    }

    @Test
    void aDomainTheCallerCantUseCostsNoCreateTokenAndNoLinkCheck() {
        final var createUrlDto = onTheDomain("go.example.com");
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/menu").build());
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        exception -> assertThat(exception.getFieldErrors()).containsExactly(entry("domain", DOMAIN_REFUSAL)));

        verify(rateLimiter, never()).tryAcquire(startsWith("create:"), anyInt(), any());
        verifyNoInteractions(urlValidator);
        verify(urlRepository, never()).save(any());
    }

    @Test
    void anApiKeyCreateOnADomainTheOwnerCantUseCostsNoCreateTokenAndNoLinkCheck() {
        final var createUrlDto = onTheDomain("go.example.com");
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/menu").build());
        when(apiKeyService.fetchApiKeyByKey("k")).thenReturn(ApiKey.builder().id(3L).owner(User.builder().id(5L).build()).build());
        when(customDomainRepository.findUsable("go.example.com", 5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.saveUrlWithApiKey(createUrlDto, "k", CreatedVia.API_KEY))
                .isInstanceOf(FieldValidationException.class);

        verify(apiKeyValidator).apiKeyExistsByKeyAndIsValid("k");
        verify(rateLimiter, never()).tryAcquire(startsWith("create:"), anyInt(), any());
        verifyNoInteractions(urlValidator);
    }

    @Test
    void aDomainTurnedOffDuringTheChecksStopsTheCreate() {
        final var createUrlDto = onTheDomain("go.example.com");
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/menu").build());
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(customDomainRepository.findUsable("go.example.com", 1L))
                .thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)), Optional.empty());

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(FieldValidationException.class);

        verify(urlValidator).checkIfLongUrlIsNotBlocklisted(any());
        verify(customDomainRepository, times(2)).findUsable("go.example.com", 1L);
        verify(urlRepository, never()).save(any());
    }

    @Test
    void bulkChecksEachItemsDomain() {
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)));
        when(customDomainRepository.findUsable("other.example.com", 1L)).thenReturn(Optional.empty());
        final var links = List.of(
                CreateUrlDto.builder().longUrl("https://example.com/1").build(),
                CreateUrlDto.builder().longUrl("https://example.com/2").domain("go.example.com").build(),
                CreateUrlDto.builder().longUrl("https://example.com/3").domain("Other.Example.com").build());

        assertThatThrownBy(() -> urlService.requireUsableDomains(links, null))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        exception -> assertThat(exception.getFieldErrors()).containsExactly(entry("domain", DOMAIN_REFUSAL)));

        verify(urlRepository, never()).save(any());
    }

    @Test
    void bulkWithAKeyChecksTheKeyOwnersDomains() {
        when(apiKeyService.fetchApiKeyByKey("k")).thenReturn(ApiKey.builder().id(3L).owner(User.builder().id(5L).build()).build());
        when(customDomainRepository.findUsable("go.example.com", 5L)).thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)));

        urlService.requireUsableDomains(List.of(onTheDomain("go.example.com"), onTheDomain("GO.example.com")), "k");

        verify(apiKeyValidator).apiKeyExistsByKeyAndIsValid("k");
        verify(customDomainRepository).findUsable("go.example.com", 5L);
        verify(userService, never()).getUserFromToken();
    }

    @Test
    void bulkWithoutDomainsLooksNothingUp() {
        urlService.requireUsableDomains(List.of(CreateUrlDto.builder().longUrl("https://example.com/1").build(), onTheDomain(" ")), "k");

        verifyNoInteractions(customDomainRepository, userService, apiKeyService, apiKeyValidator);
    }

    @Test
    void anonymousBulkWithADomainIsRefused() {
        when(userService.getUserFromToken()).thenReturn(null);

        assertThatThrownBy(() -> urlService.requireUsableDomains(List.of(onTheDomain("go.example.com")), null))
                .isInstanceOf(FieldValidationException.class);

        verifyNoInteractions(customDomainRepository);
    }

    @Test
    void theSameCustomAliasOnTwoDomainsIsFine() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").shortUrl("menu").domain("go.example.com").build();
        final var url = Url.builder().longUrl("https://example.com/menu").shortUrl("menu").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)));
        when(urlRepository.save(url)).thenReturn(persisted(url));

        final var created = urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(created.shortUrl()).isEqualTo("menu");
        assertThat(created.domain()).isEqualTo(new DomainRef(7L, "go.example.com"));
        verify(urlValidator).checkIfShortUrlIsUnique(7L, "menu");
        verify(urlValidator, never()).checkIfShortUrlIsUnique(isNull(), anyString());
    }

    @Test
    void aTakenAliasOnTheSameDomainIs409() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").shortUrl("menu").domain("go.example.com").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/menu").shortUrl("menu").build());
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)));
        org.mockito.Mockito.doThrow(new ShortUrlAlreadyExistsException(UrlValidator.SHORT_URL_TAKEN))
                .when(urlValidator).checkIfShortUrlIsUnique(7L, "menu");

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage(UrlValidator.SHORT_URL_TAKEN);

        verify(urlRepository, never()).save(any());
    }

    @Test
    void aCustomAliasLostToAConcurrentCreateOnTheSameDomainIsTaken() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").shortUrl("menu").domain("go.example.com").build();
        final var url = Url.builder().longUrl("https://example.com/menu").shortUrl("menu").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)));
        when(urlRepository.save(url)).thenReturn(persisted(url));
        org.mockito.Mockito.doThrow(shortUrlTakenMeanwhile()).when(urlRepository).flush();

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage(UrlValidator.SHORT_URL_TAKEN);
    }

    @Test
    void randomAliasesArePerDomain() {
        final var url = signedInCreate();
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)));
        org.mockito.Mockito.lenient().when(urlRepository.aliasTaken(isNull(), anyString())).thenReturn(true);

        urlService.saveUrlRouting(onTheDomain("go.example.com"), CLIENT_IP, CreatedVia.WEB);

        verify(urlRepository).aliasTaken(7L, url.getShortUrl());
        verify(urlRepository, never()).aliasTaken(isNull(), anyString());
        verify(urlRepository).save(url);
    }

    @Test
    void aRandomAliasTakenOnTheDomainIsSkipped() {
        final var url = signedInCreate();
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)));
        when(urlRepository.aliasTaken(eq(7L), anyString())).thenReturn(true, false);

        urlService.saveUrlRouting(onTheDomain("go.example.com"), CLIENT_IP, CreatedVia.WEB);

        final var candidates = ArgumentCaptor.forClass(String.class);
        verify(urlRepository, times(2)).aliasTaken(eq(7L), candidates.capture());
        assertThat(url.getShortUrl()).isEqualTo(candidates.getAllValues().getLast());
    }

    @Test
    void reservedAliasesApplyOnEveryDomain() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").shortUrl("login").domain("go.example.com").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/menu").shortUrl("login").build());
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)));
        org.mockito.Mockito.doThrow(new ShortUrlAlreadyExistsException("This short URL is reserved. Try another."))
                .when(urlValidator).checkIfShortUrlIsReserved("login");

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("This short URL is reserved. Try another.");

        verify(urlValidator).checkIfShortUrlIsUnique(7L, "login");
        verify(urlRepository, never()).save(any());
    }

    @Test
    void randomAliasesOnADomainSkipReservedWords() {
        final var url = signedInCreate();
        when(customDomainRepository.findUsable("go.example.com", 1L)).thenReturn(Optional.of(goExampleCom(CustomDomainStatus.ACTIVE)));
        when(reservedAliasService.isReserved(anyString())).thenReturn(true, false);

        urlService.saveUrlRouting(onTheDomain("go.example.com"), CLIENT_IP, CreatedVia.WEB);

        final var candidates = ArgumentCaptor.forClass(String.class);
        verify(reservedAliasService, times(2)).isReserved(candidates.capture());
        assertThat(url.getShortUrl()).isEqualTo(candidates.getAllValues().getLast());
        verify(urlRepository).aliasTaken(7L, url.getShortUrl());
    }

    @Test
    void aFreeAliasIsDrawnForTheGivenDomain() {
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.aliasTaken(isNull(), anyString())).thenReturn(true, false);

        final var alias = urlService.nextFreeShortUrl(null);

        final var candidates = ArgumentCaptor.forClass(String.class);
        verify(urlRepository, times(2)).aliasTaken(isNull(), candidates.capture());
        assertThat(alias).hasSize(5).isEqualTo(candidates.getAllValues().getLast());
        verify(reservedAliasService, times(2)).isReserved(anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = CustomDomainStatus.class, names = "ACTIVE", mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    void aLinkOnARemovedDomainCannotBeReactivated(final CustomDomainStatus status) {
        final var url = Url.builder().id(1L).shortUrl("menu").longUrl("https://example.com").visits(0L).active(false)
                .domain(goExampleCom(status)).build();
        when(urlRepository.findById(1L)).thenReturn(Optional.of(url));

        assertThatThrownBy(() -> urlService.activateUrl(1L))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(DOMAIN_OFF);

        verify(urlValidator).verifyUserAdminOrOwner(url);
        verify(urlValidator, never()).checkIfShortUrlIsFreeToReactivate(any(), any());
        verify(urlValidator, never()).checkIfUrlSafe(any());
        verify(urlRepository, never()).save(any());
        assertThat(url.isActive()).isFalse();
    }

    @Test
    void aDomainTurnedOffWhileTheLinkIsReactivatedStopsIt() {
        final var loaded = Url.builder().id(1L).shortUrl("menu").longUrl("https://example.com").visits(0L).active(false)
                .domain(goExampleCom(CustomDomainStatus.ACTIVE)).build();
        final var current = Url.builder().id(1L).shortUrl("menu").longUrl("https://example.com").visits(0L).active(false)
                .domain(goExampleCom(CustomDomainStatus.DISABLED)).build();
        when(urlRepository.findById(1L)).thenReturn(Optional.of(loaded), Optional.of(current));

        assertThatThrownBy(() -> urlService.activateUrl(1L))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(DOMAIN_OFF);

        verify(urlRepository, never()).save(any());
        assertThat(current.isActive()).isFalse();
    }

    @Test
    void aLinkOnAnActiveDomainIsReactivatedWithItsAliasCheckedOnThatDomain() {
        final var url = Url.builder().id(1L).shortUrl("menu").longUrl("https://example.com").visits(0L).active(false)
                .domain(goExampleCom(CustomDomainStatus.ACTIVE)).build();
        when(urlRepository.findById(1L)).thenReturn(Optional.of(url));
        when(urlRepository.save(url)).thenReturn(url);

        final var result = urlService.activateUrl(1L);

        verify(urlValidator).checkIfShortUrlIsFreeToReactivate(7L, "menu");
        assertThat(result.active()).isTrue();
        assertThat(result.domain()).isEqualTo(new DomainRef(7L, "go.example.com"));
    }

    @Test
    void editingNeverChangesTheDomain() {
        final var domain = goExampleCom(CustomDomainStatus.ACTIVE);
        final var url = Url.builder().id(7L).shortUrl("menu").longUrl("https://example.com").visits(0L).active(true).domain(domain).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://example.com").visitLimit(10L).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(Url.builder().id(7L).longUrl("https://example.com").visitLimit(10L).build());
        when(urlRepository.save(url)).thenReturn(url);

        final var result = urlService.updateUrl(update);

        assertThat(url.getDomain()).isSameAs(domain);
        assertThat(url.getVisitLimit()).isEqualTo(10L);
        assertThat(result.domain()).isEqualTo(new DomainRef(7L, "go.example.com"));
    }

    private static CreateUrlDto onTheDomain(final String domain) {
        return CreateUrlDto.builder().longUrl("https://example.com/menu").domain(domain).build();
    }

    private static CustomDomain goExampleCom(final CustomDomainStatus status) {
        return CustomDomain.builder().id(7L).hostname("go.example.com").hostnameKey("go.example.com").status(status).build();
    }

    private static DataIntegrityViolationException shortUrlTakenMeanwhile() {
        return new DataIntegrityViolationException("could not execute statement", new ConstraintViolationException(
                "could not execute statement", new SQLException("duplicate key value violates unique constraint \"ux_url_domain_short_url\"", "23505"),
                "ux_url_domain_short_url"));
    }

    private Url signedInCreate() {
        final var url = Url.builder().longUrl("https://example.com/menu").build();
        when(createUrlToUrlConverter.convert(any(CreateUrlDto.class))).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(persisted(url));
        return url;
    }

    private Url anonymousCreate() {
        final var url = Url.builder().longUrl("https://example.com/menu").build();
        when(createUrlToUrlConverter.convert(any(CreateUrlDto.class))).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(null);
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(persisted(url));
        return url;
    }

    private Url savedLink() {
        final var saved = org.mockito.ArgumentCaptor.forClass(Url.class);
        verify(urlRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void shouldReturnSavedUrlRandomShortUrl() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("long").build();
        final var url = Url.builder().longUrl("long").build();
        final var existingLongUrl = Url.builder().id(12L).longUrl("long").active(true).build();

        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(null);
        when(urlRepository.existsUrlByLongUrlAndActiveTrueAndOwnerIsNull(url.getLongUrl())).thenReturn(true);
        when(urlRepository.findByLongUrlAndActiveTrue(url.getLongUrl())).thenReturn(Optional.ofNullable(existingLongUrl));

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);
        assertThat(result.longUrl()).isEqualTo("long");
        assertThat(result.active()).isTrue();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reusingAnAnonymousLinkLogsNoAliasOrDestination(final CapturedOutput output) {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://secret-destination.example/path").build();
        final var url = Url.builder().longUrl("https://secret-destination.example/path").build();
        final var existingLongUrl = Url.builder().id(12L).shortUrl("secret-alias").longUrl("https://secret-destination.example/path").active(true).build();

        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(null);
        when(urlRepository.existsUrlByLongUrlAndActiveTrueAndOwnerIsNull(url.getLongUrl())).thenReturn(true);
        when(urlRepository.findByLongUrlAndActiveTrue(url.getLongUrl())).thenReturn(Optional.of(existingLongUrl));

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(result.shortUrl()).isEqualTo("secret-alias");
        assertThat(output)
                .contains("Long url already exists in DB")
                .doesNotContain("secret-alias")
                .doesNotContain("secret-destination.example");
    }

    @Test
    void shouldSaveUrlWithApiKey() {
        final var createUrlDto = CreateUrlDto.builder().shortUrl("").build();
        final var url = Url.builder().shortUrl("").build();
        final var api = "apikey";
        final var owner = User.builder().id(1L).build();
        final var apiKey = ApiKey.builder().id(3L).owner(owner).build();

        when(urlRepository.save(url)).thenReturn(persisted(url));
        when(apiKeyService.fetchApiKeyByKey(api)).thenReturn(apiKey);
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(appProperties.getShortUrlLength()).thenReturn(1L);

        final var result = urlService.saveUrlWithApiKey(createUrlDto, api, CreatedVia.API_KEY);
        assertThat(result).isInstanceOf(UrlResponse.class);

        verify(apiKeyValidator).apiKeyExistsByKeyAndIsValid(api);
        final var order = org.mockito.Mockito.inOrder(apiKeyService, urlRepository);
        order.verify(apiKeyService).recordUse(3L);
        order.verify(urlRepository).save(url);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void savingWithApiKeyLogsNoDestinationOrAlias(final CapturedOutput output) {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://secret-destination.example/path").build();
        final var url = Url.builder().longUrl("https://secret-destination.example/path").build();
        final var api = "apikey";
        final var apiKey = ApiKey.builder().owner(User.builder().id(1L).build()).build();

        when(urlRepository.save(url)).thenReturn(persisted(url));
        when(apiKeyService.fetchApiKeyByKey(api)).thenReturn(apiKey);
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(appProperties.getShortUrlLength()).thenReturn(24L);

        final var result = urlService.saveUrlWithApiKey(createUrlDto, api, CreatedVia.API_KEY);

        assertThat(output)
                .contains("Saving URL for owner id=1")
                .doesNotContain("secret-destination.example")
                .doesNotContain(result.shortUrl());
    }

    @Test
    void signedInWebLinkIsStoredAsWeb() {
        signedInCreate();

        urlService.saveUrlRouting(link(0), CLIENT_IP, CreatedVia.WEB);

        assertThat(savedLink().getCreatedVia()).isEqualTo(CreatedVia.WEB);
    }

    @Test
    void anonymousWebLinkIsStoredAsAnonymousWeb() {
        anonymousCreate();

        urlService.saveUrlRouting(link(0), CLIENT_IP, CreatedVia.WEB);

        assertThat(savedLink().getCreatedVia()).isEqualTo(CreatedVia.ANONYMOUS_WEB);
    }

    @Test
    void signedInMcpLinkIsStoredAsMcp() {
        signedInCreate();

        urlService.saveUrlRouting(link(0), null, CreatedVia.MCP);

        assertThat(savedLink().getCreatedVia()).isEqualTo(CreatedVia.MCP);
    }

    @Test
    void anonymousMcpLinkIsStoredAsAnonymousMcp() {
        anonymousCreate();

        urlService.saveUrlRouting(link(0), null, CreatedVia.MCP);

        assertThat(savedLink().getCreatedVia()).isEqualTo(CreatedVia.ANONYMOUS_MCP);
    }

    @Test
    void apiKeyLinkStoresTheGivenChannel() {
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(User.builder().id(1L).build()).build());
        stubLinkCreation();

        urlService.saveUrlWithApiKey(link(0), "apikey", CreatedVia.API_KEY);
        urlService.saveUrlWithApiKey(link(1), "apikey", CreatedVia.BULK);

        final var saved = org.mockito.ArgumentCaptor.forClass(Url.class);
        verify(urlRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(Url::getCreatedVia)
                .containsExactly(CreatedVia.API_KEY, CreatedVia.BULK);
    }

    @Test
    void reusedAnonymousLinkKeepsItsChannel() {
        final var createUrlDto = link(0);
        final var existing = Url.builder().id(12L).shortUrl("v0DB3").longUrl("https://example.com/0").active(true).build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(Url.builder().longUrl("https://example.com/0").build());
        when(urlRepository.existsUrlByLongUrlAndActiveTrueAndOwnerIsNull("https://example.com/0")).thenReturn(true);
        when(urlRepository.findByLongUrlAndActiveTrue("https://example.com/0")).thenReturn(Optional.of(existing));

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(result.id()).isEqualTo(12L);
        assertThat(existing.getCreatedVia()).isNull();
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void shouldFetchUrls() {
        final var apiKey = "apikey";
        final var user = User.builder().build();
        final var key = ApiKey.builder().owner(user).build();
        final var pageable = PageRequest.of(0, 20);
        final var url = Url.builder().build();
        final var urls = new PageImpl<>(Collections.singletonList(url));

        when(apiKeyService.fetchApiKeyByKey(apiKey)).thenReturn(key);
        when(urlRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(urls);

        final var result = urlService.getAllMyUrls(apiKey, pageable, new UrlSearchDto());
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void shouldFetchUrlsWithSearchFilter() {
        final var apiKey = "apikey";
        final var user = User.builder().build();
        final var key = ApiKey.builder().owner(user).build();
        final var pageable = PageRequest.of(0, 20);
        final var url = Url.builder().longUrl("https://example.com").build();
        final var urls = new PageImpl<>(Collections.singletonList(url));
        final var search = new UrlSearchDto();
        search.setSearch("example");

        when(apiKeyService.fetchApiKeyByKey(apiKey)).thenReturn(key);
        when(urlRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(urls);

        final var result = urlService.getAllMyUrls(apiKey, pageable, search);
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().longUrl()).isEqualTo("https://example.com");
    }

    @Test
    void shouldFetchUrlsWithActiveFilter() {
        final var apiKey = "apikey";
        final var user = User.builder().build();
        final var key = ApiKey.builder().owner(user).build();
        final var pageable = PageRequest.of(0, 20);
        final var url = Url.builder().active(true).build();
        final var urls = new PageImpl<>(Collections.singletonList(url));
        final var search = new UrlSearchDto();
        search.setActive(true);

        when(apiKeyService.fetchApiKeyByKey(apiKey)).thenReturn(key);
        when(urlRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(urls);

        final var result = urlService.getAllMyUrls(apiKey, pageable, search);
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().active()).isTrue();
    }

    @Test
    void shouldFetchUrlsWithExpiredFilter() {
        final var apiKey = "apikey";
        final var user = User.builder().build();
        final var key = ApiKey.builder().owner(user).build();
        final var pageable = PageRequest.of(0, 20);
        final var url = Url.builder().expirationDate(LocalDateTime.now().minusDays(1)).build();
        final var urls = new PageImpl<>(Collections.singletonList(url));
        final var search = new UrlSearchDto();
        search.setExpired(true);

        when(apiKeyService.fetchApiKeyByKey(apiKey)).thenReturn(key);
        when(urlRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(urls);

        final var result = urlService.getAllMyUrls(apiKey, pageable, search);
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void shouldFetchUrlsWithDateRangeFilter() {
        final var apiKey = "apikey";
        final var user = User.builder().build();
        final var key = ApiKey.builder().owner(user).build();
        final var pageable = PageRequest.of(0, 20);
        final var url = Url.builder().createDate(LocalDateTime.now()).build();
        final var urls = new PageImpl<>(Collections.singletonList(url));
        final var search = new UrlSearchDto();
        search.setDateFrom(LocalDateTime.now().minusDays(7));
        search.setDateTo(LocalDateTime.now().plusDays(1));

        when(apiKeyService.fetchApiKeyByKey(apiKey)).thenReturn(key);
        when(urlRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(urls);

        final var result = urlService.getAllMyUrls(apiKey, pageable, search);
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void shouldSaveUrlForLoggedInUserWithoutApiKey() {
        final var createUrlDto = CreateUrlDto.builder().shortUrl("").build();
        final var url = Url.builder().shortUrl("").build();
        final var user = User.builder().id(1L).build();

        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(user);
        when(urlRepository.save(url)).thenReturn(persisted(url));
        when(appProperties.getShortUrlLength()).thenReturn(1L);

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);
        assertThat(result).isInstanceOf(UrlResponse.class);

        verify(apiKeyValidator, org.mockito.Mockito.never()).apiKeyExistsByKeyAndIsValid(any());
        verify(apiKeyService, org.mockito.Mockito.never()).recordUse(any());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void savingForSignedInUserLogsNoDestinationOrAlias(final CapturedOutput output) {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://secret-destination.example/path").build();
        final var url = Url.builder().longUrl("https://secret-destination.example/path").build();
        final var user = User.builder().id(1L).build();

        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(user);
        when(urlRepository.save(url)).thenReturn(persisted(url));
        when(appProperties.getShortUrlLength()).thenReturn(24L);

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB);

        assertThat(output)
                .contains("Saving URL for owner id=1")
                .doesNotContain("secret-destination.example")
                .doesNotContain(result.shortUrl());
    }

    @Test
    void theEleventhAnonymousLinkFromOneNetworkWithinAnHourIsRefusedAndNotSaved() {
        final var limiter = new CountingRateLimiter();
        final var service = urlServiceLimitedBy(limiter);
        stubLinkCreation();

        IntStream.range(0, 10).forEach(index -> service.saveUrlRouting(link(index), CLIENT_IP, CreatedVia.WEB));

        assertThatThrownBy(() -> service.saveUrlRouting(link(10), CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(CREATE_LIMIT_REFUSAL);
        verify(urlRepository, times(10)).save(any(Url.class));

        assertThat(service.saveUrlRouting(link(11), "198.51.100.4", CreatedVia.WEB).longUrl()).isEqualTo("https://example.com/11");
        assertThat(limiter.counts).containsOnlyKeys(
                RateLimiter.clientKey("create:anon", CLIENT_IP),
                RateLimiter.clientKey("create:anon", "198.51.100.4"));
        assertThat(limiter.windows).containsOnly(Duration.ofHours(1));
    }

    @Test
    void theSixtyFirstLinkByOneAccountWithinAnHourIsRefusedOnTheWebAndWithAnApiKey() {
        final var limiter = new CountingRateLimiter();
        final var service = urlServiceLimitedBy(limiter);
        final var owner = User.builder().id(1L).authorities(List.of(Authorities.builder().name("ROLE_USER").build())).build();
        final var apiKey = ApiKey.builder().id(3L).owner(owner).build();
        stubLinkCreation();
        when(userService.getUserFromToken()).thenReturn(owner);
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(apiKey);

        IntStream.range(0, 30).forEach(index -> service.saveUrlRouting(link(index), CLIENT_IP, CreatedVia.WEB));
        IntStream.range(30, 60).forEach(index -> service.saveUrlWithApiKey(link(index), "apikey", CreatedVia.API_KEY));

        assertThatThrownBy(() -> service.saveUrlWithApiKey(link(60), "apikey", CreatedVia.API_KEY))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(CREATE_LIMIT_REFUSAL);
        assertThatThrownBy(() -> service.saveUrlRouting(link(61), CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(CREATE_LIMIT_REFUSAL);
        verify(urlRepository, times(60)).save(any(Url.class));
        verify(apiKeyService, times(30)).recordUse(3L);
        assertThat(limiter.counts).containsOnlyKeys("create:user:1");
        assertThat(limiter.windows).containsOnly(Duration.ofHours(1));
    }

    @Test
    void adminsAreNeverLimited() {
        final var limiter = new CountingRateLimiter();
        final var service = urlServiceLimitedBy(limiter);
        final var admin = User.builder().id(2L).authorities(List.of(Authorities.builder().name("ROLE_ADMIN").build())).build();
        stubLinkCreation();
        when(userService.getUserFromToken()).thenReturn(admin);
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(admin).build());

        IntStream.range(0, 61).forEach(index -> service.saveUrlRouting(link(index), CLIENT_IP, CreatedVia.WEB));
        IntStream.range(61, 122).forEach(index -> service.saveUrlWithApiKey(link(index), "apikey", CreatedVia.API_KEY));

        verify(urlRepository, times(122)).save(any(Url.class));
        assertThat(limiter.counts).isEmpty();
    }

    @Test
    void eachCreateCountsOnceSoABulkRequestCountsEveryLink() {
        final var limiter = new CountingRateLimiter();
        final var service = urlServiceLimitedBy(limiter);
        final var owner = User.builder().id(1L).build();
        stubLinkCreation();
        when(userService.getUserFromToken()).thenReturn(owner);

        List.of(link(0), link(1), link(2)).forEach(dto -> service.saveUrlRouting(dto, CLIENT_IP, CreatedVia.WEB));

        assertThat(limiter.counts).containsExactlyEntriesOf(Map.of("create:user:1", 3));
    }

    @Test
    void linksAreStillCreatedWhenTheRateLimiterIsDown() {
        stubLinkCreation();
        when(rateLimiter.tryAcquire(startsWith("create:"), anyInt(), any()))
                .thenThrow(new RateLimiterUnavailableException(new RuntimeException("down")));

        final var anonymous = urlService.saveUrlRouting(link(0), CLIENT_IP, CreatedVia.WEB);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        final var signedIn = urlService.saveUrlRouting(link(1), CLIENT_IP, CreatedVia.WEB);

        assertThat(anonymous.longUrl()).isEqualTo("https://example.com/0");
        assertThat(signedIn.longUrl()).isEqualTo("https://example.com/1");
        verify(rateLimiter).tryAcquire(RateLimiter.clientKey("create:anon", CLIENT_IP), 10, Duration.ofHours(1));
        verify(rateLimiter).tryAcquire("create:user:1", 60, Duration.ofHours(1));
        verify(urlRepository, times(2)).save(any(Url.class));
    }

    @Test
    void anonymousLinksWithoutAClientIpAreLeftToTheCallersOwnLimit() {
        stubLinkCreation();

        urlService.saveUrlRouting(link(0), null, CreatedVia.WEB);

        verify(rateLimiter, never()).tryAcquire(startsWith("create:"), anyInt(), any());
        verify(urlRepository).save(any(Url.class));
    }

    @Test
    void reactivationChecksTheBlockTheAliasAndSafetyBeforeSaving() {
        final var url = Url.builder().id(1L).shortUrl("promo").longUrl("https://example.com")
                .threatType(cc.hrva.urlshortener.model.enums.ThreatType.MALWARE)
                .blockReason(cc.hrva.urlshortener.model.enums.BlockReason.SAFE_BROWSING).blockDetail("MALWARE")
                .blockedAt(java.time.LocalDateTime.now()).active(false).build();
        when(urlRepository.findById(1L)).thenReturn(Optional.of(url));
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));

        final var result = urlService.activateUrl(1L);

        final var order = org.mockito.Mockito.inOrder(urlValidator, urlRepository);
        order.verify(urlValidator).verifyUserAdminOrOwner(url);
        order.verify(urlValidator).checkIfUrlNotBlocked(url);
        order.verify(urlValidator).checkIfShortUrlIsFreeToReactivate(null, "promo");
        order.verify(urlValidator).checkIfDestinationNotBlocked(url);
        order.verify(urlValidator).checkIfUrlSafe(url);
        order.verify(urlRepository).save(url);
        assertThat(result.active()).isTrue();
        assertThat(url.getThreatType()).isNull();
        assertThat(url.getBlockReason()).isNull();
        assertThat(url.getBlockDetail()).isNull();
        assertThat(url.getBlockedAt()).isNull();
    }

    @Test
    void shouldActivateUrl() {
        final var url = Url.builder().id(1L).active(false).build();
        final var activatedUrl = Url.builder().id(1L).active(true).build();
        when(urlRepository.findById(1L)).thenReturn(Optional.of(url));
        when(urlRepository.save(any(Url.class))).thenReturn(activatedUrl);

        final var result = urlService.activateUrl(1L);
        assertThat(result.active()).isTrue();
    }

    @Test
    void unlockStopsGuessingAfterTooManyAttempts() {
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.startsWith("unlock:"), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> urlService.unlockUrl(null, "board", "guess", CLICK))
                .isInstanceOf(cc.hrva.urlshortener.exception.RateLimitExceededException.class)
                .hasMessage("Too many password attempts. Try again in a few minutes.");
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void unlockStillWorksWhenTheRateLimiterIsDown() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(rateLimiter.tryAcquire(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new RateLimiterUnavailableException(new RuntimeException("down")));
        when(urlRepository.findActive(null, "a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);

        assertThat(urlService.unlockUrl(null, "a", "secret", CLICK).longUrl()).isEqualTo("https://a.com");
    }

    @Test
    void shouldUnlockWithCorrectPassword() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(urlRepository.findActive(null, "a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        final var result = urlService.unlockUrl(null, "a", "pw", CLICK);

        assertThat(result.longUrl()).isEqualTo("https://a.com");
    }

    @Test
    void shouldRejectUnlockWithWrongPassword() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(urlRepository.findActive(null, "a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("nope", "hash")).thenReturn(false);

        assertThatThrownBy(() -> urlService.unlockUrl(null, "a", "nope", CLICK))
                .isInstanceOf(cc.hrva.urlshortener.exception.NoAuthorizationException.class);
    }

    @Test
    void shouldUnlockNonProtectedUrlWithoutPasswordCheck() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).build();
        when(urlRepository.findActive(null, "a")).thenReturn(Optional.of(url));

        final var result = urlService.unlockUrl(null, "a", null, CLICK);

        assertThat(result.longUrl()).isEqualTo("https://a.com");
    }

    @Test
    void unlockRateLimitKeysOnTheClientOfTheClick() {
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(urlRepository.findActive(null, "a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        urlService.unlockUrl(null, "a", "pw", CLICK);

        verify(rateLimiter).tryAcquire(RateLimiter.clientKey("unlock:a", CLIENT_IP), 10, Duration.ofMinutes(15));
        verify(rateLimiter).tryAcquire("unlock:a", 50, Duration.ofMinutes(15));
    }

    @Test
    void unlockRecordsTheClickWithAnUnknownReferrer() {
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(urlRepository.findActive(null, "a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        assertThat(urlService.unlockUrl(null, "a", "pw", CLICK).longUrl()).isEqualTo("https://a.com");

        verify(clickAnalyticsService).recordClick(7L, "a", CLICK.withUnknownReferrer());
    }

    @Test
    void unlockAnswersABotOnAVisitLimitedLinkAsMissing() {
        final var url = Url.builder().id(7L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").visits(0L).visitLimit(1L).build();
        when(urlRepository.findActive(null, "a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(clickAnalyticsService.isBot(CLICK)).thenReturn(true);

        assertThatThrownBy(() -> urlService.unlockUrl(null, "a", "pw", CLICK))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessage("URL doesn't exist");

        verify(clickAnalyticsService).recordClick(7L, "a", CLICK.withUnknownReferrer());
        verify(clickCheckService, never()).mayRedirect(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void shouldReturnMyDistinctTags() {
        final var user = User.builder().id(1L).email("u@example.com").build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(urlRepository.findDistinctTagsByOwner(user)).thenReturn(java.util.List.of("work", "campaign"));

        final var tags = urlService.getMyTags(null);

        assertThat(tags).containsExactly("work", "campaign");
    }

    @Test
    void shouldRejectMalformedLongUrlWhenSavingUrl() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https//wedding.hrva.cc").build();
        when(appProperties.isAnonymousUrlCreationEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto, CLIENT_IP, CreatedVia.WEB))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("That doesn't look like a valid web address. Check the URL and try again.");

        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void shouldRejectMalformedLongUrlWhenSavingUrlWithApiKey() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https//wedding.hrva.cc").build();
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(User.builder().id(1L).build()).build());

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "apikey", CreatedVia.API_KEY))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("That doesn't look like a valid web address. Check the URL and try again.");

        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void shouldRejectLinksToOtherShortenersWhenSavingUrlWithApiKey() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://bit.ly/3abc").build();
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(ApiKey.builder().owner(User.builder().id(1L).build()).build());

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "apikey", CreatedVia.API_KEY))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("Links to other URL shorteners can't be shortened. Use the final destination instead.");

        verify(linkCheckService, never()).check(anyString());
        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void adminListFlagsSuspiciousLinks() {
        final var pageable = PageRequest.of(0, 20);
        final var url = Url.builder().id(287L).shortUrl("njuskaio_verify").longUrl("https://njuskaio.cam/a/MAUB3").build();
        when(urlRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(url)));
        when(suspiciousUrlService.findReasons("https://njuskaio.cam/a/MAUB3")).thenReturn(List.of("imitates njuskalo"));

        final var result = urlService.getAllUrls(pageable, new UrlSearchDto());

        assertThat(result.getContent().getFirst().suspiciousReasons()).containsExactly("imitates njuskalo");
    }

    @Test
    void adminListShowsWhyALinkIsBlockedItsOpenReviewItsOwnersScammerSignalAndWhereItEnds() {
        final var pageable = PageRequest.of(0, 20);
        final var scammer = User.builder().id(27L).email("rhea@gmail.com").build();
        final var honest = User.builder().id(28L).email("ana@example.com").build();
        final var blocked = Url.builder().id(287L).shortUrl("v0DB3").longUrl("https://findfavoritedate.life/?u=f6yp608").owner(scammer)
                .blockReason(BlockReason.BLOCKLIST).finalUrl("https://findfavoritedate.life/landing").build();
        final var inReview = Url.builder().id(288L).shortUrl("menu").longUrl("https://gableci.hrva.cc").owner(honest).active(true).build();
        final var anonymous = Url.builder().id(289L).shortUrl("anon").longUrl("https://example.org").active(true).build();
        when(urlRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(blocked, inReview, anonymous)));
        when(reviewQueueService.openUrlIds(List.of(287L, 288L, 289L))).thenReturn(Set.of(288L));
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of(27L));

        final var rows = urlService.getAllUrls(pageable, new UrlSearchDto()).getContent();

        assertThat(rows).extracting(UrlResponse::blockReason, UrlResponse::reviewOpen, UrlResponse::ownerPossibleScammer, UrlResponse::finalUrl)
                .containsExactly(
                        tuple(BlockReason.BLOCKLIST, false, true, "https://findfavoritedate.life/landing"),
                        tuple(null, true, false, null),
                        tuple(null, false, false, null));
        verify(reviewQueueService).openUrlIds(any());
        verify(scammerSignalService).possibleScammerIds();
    }

    @Test
    void adminListOfAnonymousLinksAsksNothingAboutScammers() {
        final var pageable = PageRequest.of(0, 20);
        final var anonymous = Url.builder().id(289L).shortUrl("anon").longUrl("https://example.org").active(true).build();
        when(urlRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(anonymous)));

        final var rows = urlService.getAllUrls(pageable, new UrlSearchDto()).getContent();

        assertThat(rows).singleElement().extracting(UrlResponse::ownerPossibleScammer).isEqualTo(false);
        verifyNoInteractions(scammerSignalService);
    }

    @Test
    void publicLookupHidesTheOwnerAndTags() {
        final var owner = User.builder().email("owner@example.com").build();
        final var url = Url.builder().id(3L).shortUrl("menu").longUrl("https://gableci.hrva.cc").owner(owner).tags(java.util.Set.of("client-x")).active(true).build();
        when(urlRepository.findActive(null, "menu")).thenReturn(Optional.of(url));

        final var result = urlService.revealUrlAndRecordClick(null, "menu", CLICK);

        assertThat(result.longUrl()).isEqualTo("https://gableci.hrva.cc");
        assertThat(result.ownerEmail()).isNull();
        assertThat(result.tags()).isEmpty();
    }

    @Test
    void revealRecordsTheClickWithTheRequestDetails() {
        final var url = Url.builder().id(3L).shortUrl("menu").longUrl("https://gableci.hrva.cc").active(true).build();
        when(urlRepository.findActive(null, "menu")).thenReturn(Optional.of(url));

        urlService.revealUrlAndRecordClick(null, "menu", CLICK);

        verify(clickAnalyticsService).recordClick(3L, "menu", CLICK);
        verify(clickAnalyticsService, never()).isBot(any());
    }

    @Test
    void revealAnswersABotOnAVisitLimitedLinkAsMissing() {
        final var url = Url.builder().id(3L).shortUrl("menu").longUrl("https://gableci.hrva.cc").visits(0L).visitLimit(1L).active(true).build();
        when(urlRepository.findActive(null, "menu")).thenReturn(Optional.of(url));
        when(clickAnalyticsService.isBot(CLICK)).thenReturn(true);

        assertThatThrownBy(() -> urlService.revealUrlAndRecordClick(null, "menu", CLICK))
                .isInstanceOf(UrlNotFoundException.class)
                .hasMessage("URL doesn't exist");

        verify(clickAnalyticsService).recordClick(3L, "menu", CLICK);
    }

    @Test
    void publicLookupOfALinkFoundOnABlocklistIsNotFoundAndRecordsNoClick() {
        final var url = Url.builder().id(3L).shortUrl("menu").longUrl("https://jofogas.cards/login").active(true).build();
        when(urlRepository.findActive(null, "menu")).thenReturn(Optional.of(url));
        when(clickCheckService.mayRedirect(3L, "https://jofogas.cards/login")).thenReturn(false);

        assertThatThrownBy(() -> urlService.revealUrlAndRecordClick(null, "menu", CLICK))
                .isInstanceOf(cc.hrva.urlshortener.exception.UrlNotFoundException.class)
                .hasMessage("URL doesn't exist");

        verifyNoInteractions(clickAnalyticsService);
    }

    @Test
    void peekingAtALinkFoundOnABlocklistIsNotFound() {
        final var url = Url.builder().id(3L).shortUrl("menu").longUrl("https://jofogas.cards/login").active(true).build();
        when(urlRepository.findActive(null, "menu")).thenReturn(Optional.of(url));
        when(clickCheckService.mayRedirect(3L, "https://jofogas.cards/login")).thenReturn(false);

        assertThatThrownBy(() -> urlService.peekUrlByShortUrl(null, "menu"))
                .isInstanceOf(cc.hrva.urlshortener.exception.UrlNotFoundException.class)
                .hasMessage("URL doesn't exist");

        verify(urlToPeekUrlConverter, never()).convert(any());
    }

    @Test
    void peekingAtALinkThatPassesTheClickCheckShowsIt() {
        final var url = Url.builder().id(3L).shortUrl("menu").longUrl("https://gableci.hrva.cc").active(true).build();
        final var peek = cc.hrva.urlshortener.model.PeekUrl.builder().shortUrl("menu").longUrl("https://gableci.hrva.cc").build();
        when(urlRepository.findActive(null, "menu")).thenReturn(Optional.of(url));
        when(urlToPeekUrlConverter.convert(url)).thenReturn(peek);

        assertThat(urlService.peekUrlByShortUrl(null, "menu")).isSameAs(peek);

        verify(clickCheckService).mayRedirect(3L, "https://gableci.hrva.cc");
    }

    @Test
    void lookingUpAPasswordProtectedLinkRevealsNothingSoSkipsTheClickCheck() {
        final var url = Url.builder().id(4L).shortUrl("board").longUrl("https://secret.example/board").passwordHash("$2a$10$hash").active(true).build();
        when(urlRepository.findActive(null, "board")).thenReturn(Optional.of(url));

        urlService.revealUrlAndRecordClick(null, "board", CLICK);
        urlService.peekUrlByShortUrl(null, "board");

        verify(clickCheckService, never()).mayRedirect(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void passwordProtectedRevealRecordsNoClick() {
        final var url = Url.builder().id(4L).shortUrl("board").longUrl("https://secret.example/board").passwordHash("$2a$10$hash").visitLimit(1L).active(true).build();
        when(urlRepository.findActive(null, "board")).thenReturn(Optional.of(url));

        final var result = urlService.revealUrlAndRecordClick(null, "board", CLICK);

        assertThat(result.longUrl()).isNull();
        assertThat(result.passwordProtected()).isTrue();
        verify(clickAnalyticsService, never()).recordClick(any(), any(), any());
        verify(clickAnalyticsService, never()).isBot(any());
    }

    @Test
    void adminListSortsAnonymousLinksLastWhenOrderingByOwner() {
        final var pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("owner.email"), Sort.Order.asc("visits")));
        final var sent = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(urlRepository.findAll(sent.capture())).thenReturn(new PageImpl<>(List.of()));

        urlService.getAllUrls(pageable, new UrlSearchDto());

        assertThat(sent.getValue().getSort().getOrderFor("owner.email").getNullHandling()).isEqualTo(Sort.NullHandling.NULLS_LAST);
        assertThat(sent.getValue().getSort().getOrderFor("owner.email").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(sent.getValue().getSort().getOrderFor("visits").getNullHandling()).isEqualTo(Sort.NullHandling.NATIVE);
    }

    @Test
    void redirectFallsBackToTheDatabaseWhenTheCacheIsDown() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        when(cache.get("abc")).thenThrow(new IllegalStateException("Redis is down"));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis is down")).when(cache).put(any(), any());
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).build();
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(url));

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", CLICK);

        assertThat(redirect.getUrl()).isEqualTo("https://example.com/a");
    }

    @Test
    void redirectRecordsTheClickWithTheRequestDetails() {
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).build();
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(url));

        assertThat(urlServiceWithMeters().redirectResultUrl("abc", CLICK).getUrl()).isEqualTo("https://example.com/a");

        verify(clickAnalyticsService).recordClick(7L, "abc", CLICK);
    }

    @Test
    void redirectFromTheCacheRecordsTheClickByIdWithoutReadingTheLink() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var cached = new UrlResponse(5L, "https://example.com/a", "abc", null, null, 0L, 3L, true, "ACTIVE", java.util.Set.of(), false, null);
        when(cache.get("abc")).thenReturn(() -> cached);

        assertThat(urlServiceWithMeters().redirectResultUrl("abc", CLICK).getUrl()).isEqualTo("https://example.com/a");

        verify(clickAnalyticsService).recordClick(5L, "abc", CLICK);
        verifyNoInteractions(urlRepository);
    }

    @Test
    void redirectSendsABotOnAVisitLimitedLinkToTheMissingPage() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).visitLimit(1L).active(true).build();
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(url));
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(clickAnalyticsService.isBot(CLICK)).thenReturn(true);

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", CLICK);

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=missing");
        verify(clickAnalyticsService).recordClick(7L, "abc", CLICK);
        verifyNoInteractions(clickCheckService);
        verify(cache, never()).put(any(), any());
    }

    @Test
    void redirectFromTheCacheSendsABotOnAVisitLimitedLinkToTheMissingPage() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var cached = new UrlResponse(5L, "https://example.com/a", "abc", null, null, 0L, 1L, true, "ACTIVE", java.util.Set.of(), false, null);
        when(cache.get("abc")).thenReturn(() -> cached);
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(clickAnalyticsService.isBot(CLICK)).thenReturn(true);

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", CLICK);

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=missing");
        verify(clickAnalyticsService).recordClick(5L, "abc", CLICK);
        verifyNoInteractions(clickCheckService, urlRepository);
    }

    @Test
    void redirectSendsABotOnALinkWithoutALimitToTheDestination() {
        final var bot = ClickRequest.of(CLIENT_IP, "Discordbot/2.0", null, "HR");
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).build();
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(url));

        assertThat(urlServiceWithMeters().redirectResultUrl("abc", bot).getUrl()).isEqualTo("https://example.com/a");

        verify(clickAnalyticsService, never()).isBot(any());
        verify(clickAnalyticsService).recordClick(7L, "abc", bot);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void clickPathsNeverLogTheClientIp(final CapturedOutput output) {
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).build();
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(url));
        when(urlRepository.findActive(null, "abc")).thenReturn(Optional.of(url));

        urlServiceWithMeters().redirectResultUrl("abc", CLICK);
        urlService.revealUrlAndRecordClick(null, "abc", CLICK);
        urlService.unlockUrl(null, "abc", null, CLICK);

        assertThat(output)
                .contains("Redirect shortUrl=abc")
                .contains("URL access shortUrl=abc")
                .doesNotContain(CLIENT_IP);
    }

    @Test
    void aCachedLinkFoundOnABlocklistAtClickTimeGoesToTheUnavailablePageWithoutRecordingTheClick() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var cached = new UrlResponse(5L, "https://jofogas.cards/login", "abc", null, null, 0L, null, true, "ACTIVE", java.util.Set.of(), false, null);
        when(cache.get("abc")).thenReturn(() -> cached);
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(clickCheckService.mayRedirect(5L, "https://jofogas.cards/login")).thenReturn(false);

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", CLICK);

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=blocked");
        verify(clickAnalyticsService, never()).recordClick(any(), any(), any());
    }

    @Test
    void aDatabaseLinkFoundOnABlocklistAtClickTimeGoesToTheUnavailablePageAndIsNeverCached() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://jofogas.cards/login").visits(0L).active(true).build();
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(url));
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(clickCheckService.mayRedirect(5L, "https://jofogas.cards/login")).thenReturn(false);

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", CLICK);

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=blocked");
        verify(cache, never()).put(any(), any());
        verify(clickAnalyticsService, never()).recordClick(any(), any(), any());
    }

    @Test
    void aDatabaseLinkThatPassesTheClickCheckIsCachedAndRecorded() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).build();
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(url));

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", CLICK);

        assertThat(redirect.getUrl()).isEqualTo("https://example.com/a");
        verify(clickCheckService).mayRedirect(5L, "https://example.com/a");
        verify(cache).put(eq("abc"), any(UrlResponse.class));
        verify(clickAnalyticsService).recordClick(5L, "abc", CLICK);
    }

    @Test
    void aPasswordProtectedLinkGoesToItsGateWithoutTheClickCheck() {
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).passwordHash("hash").build();
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(url));
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", CLICK);

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/protected/abc");
        verify(clickCheckService, never()).mayRedirect(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void unlockingALinkFoundOnABlocklistAtClickTimeSendsTheVisitorToTheUnavailablePage() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(urlRepository.findActive(null, "a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(clickCheckService.mayRedirect(1L, "https://a.com")).thenReturn(false);

        final var result = urlService.unlockUrl(null, "a", "pw", CLICK);

        assertThat(result.longUrl()).isEqualTo(FRONTEND + "/unavailable?s=a&reason=blocked");
        verify(clickAnalyticsService, never()).recordClick(any(), any(), any());
    }

    @Test
    void aWrongPasswordNeverReachesTheClickCheck() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(urlRepository.findActive(null, "a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("nope", "hash")).thenReturn(false);

        assertThatThrownBy(() -> urlService.unlockUrl(null, "a", "nope", CLICK))
                .isInstanceOf(NoAuthorizationException.class);

        verify(clickCheckService, never()).mayRedirect(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void aBlockedLinkGoesToTheUnavailablePageSayingItWasBlocked() {
        final var url = storedLink().active(false).blockReason(BlockReason.BLOCKLIST).build();

        assertThat(redirectOf(url)).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=blocked");
    }

    @Test
    void aLinkSafeBrowsingFlaggedBeforeBlockReasonsExistedSaysBlocked() {
        final var url = storedLink().active(false).threatType(cc.hrva.urlshortener.model.enums.ThreatType.MALWARE).build();

        assertThat(redirectOf(url)).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=blocked");
    }

    @Test
    void anExpiredLinkSaysExpired() {
        final var url = storedLink().active(false).expirationDate(LocalDateTime.now().minusDays(1)).build();

        assertThat(redirectOf(url)).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=expired");
    }

    @Test
    void anExpiredLinkTheSchedulerHasNotSwitchedOffYetSaysExpiredAndIsNotCached() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var url = storedLink().active(true).expirationDate(LocalDateTime.now().minusSeconds(10)).build();

        assertThat(redirectOf(url)).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=expired");
        verify(cache, never()).put(any(), any());
        verify(clickCheckService, never()).mayRedirect(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void aLinkThatReachedItsVisitLimitSaysLimit() {
        final var url = storedLink().active(false).visits(3L).visitLimit(3L).build();

        assertThat(redirectOf(url)).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=limit");
    }

    @Test
    void aLinkItsOwnerSwitchedOffSaysInactive() {
        final var url = storedLink().active(false).build();

        assertThat(redirectOf(url)).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=inactive");
    }

    @Test
    void anUnknownShortLinkSaysMissing() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        final var redirect = urlServiceWithMeters().redirectResultUrl("nope", CLICK);

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=nope&reason=missing");
    }

    @Test
    void anUnknownShortLinkCostsOneQuery() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        urlServiceWithMeters().redirectResultUrl("nope", CLICK);

        verify(urlRepository).findServed(null, "nope");
        org.mockito.Mockito.verifyNoMoreInteractions(urlRepository);
    }

    @Test
    void anActiveLinkCostsOneQueryEvenWithAnOlderInactiveDuplicate() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).build();
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(url));

        assertThat(urlServiceWithMeters().redirectResultUrl("abc", CLICK).getUrl()).isEqualTo("https://example.com/a");

        verify(urlRepository).findServed(null, "abc");
        org.mockito.Mockito.verifyNoMoreInteractions(urlRepository);
    }

    @Test
    void aLongAbusivePathIsCutTo32CharactersOnTheUnavailablePage() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        final var redirect = urlServiceWithMeters().redirectResultUrl("a".repeat(31) + "é" + "b".repeat(8000), CLICK);

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=" + "a".repeat(31) + "%C3%A9&reason=missing");
    }

    @Test
    void theShortLinkIsUrlEncodedOnTheUnavailablePage() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        final var redirect = urlServiceWithMeters().redirectResultUrl("a b&c=d+é", CLICK);

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=a+b%26c%3Dd%2B%C3%A9&reason=missing");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("storedDestinations")
    void aDestinationFromTheDatabaseGoesOutAsAnAsciiLocation(final String stored, final String location) throws Exception {
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(storedLink().longUrl(stored).active(true).build()));

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", CLICK);

        assertThat(locationOf(redirect)).isEqualTo(location);
        verify(clickCheckService).mayRedirect(5L, stored);
        verify(clickAnalyticsService).recordClick(5L, "abc", CLICK);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("storedDestinations")
    void aCachedDestinationGoesOutAsAnAsciiLocation(final String stored, final String location) throws Exception {
        cached(stored);

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", CLICK);

        assertThat(locationOf(redirect)).isEqualTo(location);
        verify(clickCheckService).mayRedirect(5L, stored);
        verifyNoInteractions(urlRepository);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aDestinationFromTheDatabaseWhoseHostCannotBeConvertedIsSentAsStored(final CapturedOutput output) {
        final var stored = "https://" + "č".repeat(64) + ".hr/torta";
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(storedLink().longUrl(stored).active(true).build()));

        assertThat(urlServiceWithMeters().redirectResultUrl("abc", CLICK).getUrl()).isEqualTo(stored);
        assertThat(output).contains("urlId=5");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aCachedDestinationWhoseHostCannotBeConvertedIsSentAsStored(final CapturedOutput output) {
        final var stored = "https://" + "č".repeat(64) + ".hr/torta";
        cached(stored);

        assertThat(urlServiceWithMeters().redirectResultUrl("abc", CLICK).getUrl()).isEqualTo(stored);
        assertThat(output).contains("urlId=5");
    }

    @Test
    void aDestinationFromTheDatabaseSentAsStoredNeverHasItsBracesExpanded() throws Exception {
        final var stored = "https://" + "č".repeat(64) + ".hr/{x}";
        when(urlRepository.findServed(null, "abc")).thenReturn(Optional.of(storedLink().longUrl(stored).active(true).build()));

        assertThat(locationOf(urlServiceWithMeters().redirectResultUrl("abc", CLICK))).isEqualTo(stored);
    }

    @Test
    void aCachedDestinationSentAsStoredNeverHasItsBracesExpanded() throws Exception {
        final var stored = "https://" + "č".repeat(64) + ".hr/{x}";
        cached(stored);

        assertThat(locationOf(urlServiceWithMeters().redirectResultUrl("abc", CLICK))).isEqualTo(stored);
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> storedDestinations() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("https://čokolada.hr/torta", "https://xn--okolada-i6a.hr/torta"),
                org.junit.jupiter.params.provider.Arguments.of("https://example.com/čokolada/torta?okus=višnja#recept",
                        "https://example.com/%C4%8Dokolada/torta?okus=vi%C5%A1nja#recept"),
                org.junit.jupiter.params.provider.Arguments.of("https://example.com/%C4%8Dokolada?okus=vi%C5%A1nja",
                        "https://example.com/%C4%8Dokolada?okus=vi%C5%A1nja"),
                org.junit.jupiter.params.provider.Arguments.of("https://example.com/menu?day=1&meal=lunch#today",
                        "https://example.com/menu?day=1&meal=lunch#today"),
                org.junit.jupiter.params.provider.Arguments.of("https://example.com/{x}/menu?day={y}", "https://example.com/%7Bx%7D/menu?day=%7By%7D"));
    }

    private void cached(final String longUrl) {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var cached = new UrlResponse(5L, longUrl, "abc", null, null, 0L, null, true, "ACTIVE", java.util.Set.of(), false, null);
        when(cache.get("abc")).thenReturn(() -> cached);
    }

    private static String locationOf(final org.springframework.web.servlet.view.RedirectView redirect) throws Exception {
        final var response = new org.springframework.mock.web.MockHttpServletResponse();
        redirect.render(Map.of(), new org.springframework.mock.web.MockHttpServletRequest(), response);
        return response.getHeader(org.springframework.http.HttpHeaders.LOCATION);
    }

    @Test
    void theRootGoesToTheFrontendHomeWithItsSlash() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        assertThat(urlServiceWithMeters().redirectResultUrl(null, null).getUrl()).isEqualTo(FRONTEND + "/");
    }

    @Test
    void theRootKeepsASingleSlashWhenTheFrontendUrlHasOne() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND + "/");

        assertThat(urlServiceWithMeters().redirectResultUrl(null, null).getUrl()).isEqualTo(FRONTEND + "/");
    }

    @Test
    void noRedirectPassesTheVisitorsQueryOn() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var cached = new UrlResponse(5L, "https://example.com/a", "cached", null, null, 0L, null, true, "ACTIVE", Set.of(), false, null);
        final var gated = new UrlResponse(6L, "https://example.com/b", "gated", null, null, 0L, null, true, "ACTIVE", Set.of(), true, null);
        when(cache.get("cached")).thenReturn(() -> cached);
        when(cache.get("gated")).thenReturn(() -> gated);
        when(urlRepository.findServed(null, "stored"))
                .thenReturn(Optional.of(storedLink().shortUrl("stored").longUrl("https://example.com/c").active(true).build()));
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        final var service = urlServiceWithMeters();
        final var scan = CLICK.withQrScan();

        final var redirects = Stream.of("cached", "gated", "stored", null)
                .map(shortUrl -> service.redirectResultUrl(shortUrl, scan))
                .toList();

        assertThat(redirects)
                .extracting(RedirectView::getUrl, RedirectView::isPropagateQueryProperties)
                .containsExactly(
                        tuple("https://example.com/a", false),
                        tuple(FRONTEND + "/protected/gated", false),
                        tuple("https://example.com/c", false),
                        tuple(FRONTEND + "/", false));
    }

    @Test
    void aBotProbeInCapitalsIsRecognisedUnderATurkishServerLocale() {
        final var serverLocale = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));

            assertThatThrownBy(() -> urlServiceWithMeters().redirectResultUrl("FAVICON.ICO", CLICK))
                    .isInstanceOf(cc.hrva.urlshortener.exception.UrlNotFoundException.class);
        } finally {
            java.util.Locale.setDefault(serverLocale);
        }

        org.mockito.Mockito.verifyNoInteractions(urlRepository);
    }

    @Test
    void botProbesStillGetANotFound() {
        assertThatThrownBy(() -> urlServiceWithMeters().redirectResultUrl("favicon.ico", CLICK))
                .isInstanceOf(cc.hrva.urlshortener.exception.UrlNotFoundException.class);

        org.mockito.Mockito.verifyNoInteractions(urlRepository);
    }

    @Test
    void sameAliasOnTwoHostsRedirectsToEach() {
        final var urls = new ConcurrentMapCacheManager("urls");
        final var service = urlServiceCachingIn(urls);
        when(urlRepository.findServed(null, "menu")).thenReturn(Optional.of(onHrva()));
        when(urlRepository.findServed(7L, "menu")).thenReturn(Optional.of(onGoExampleCom()));

        assertThat(service.redirectResultUrl("menu", CLICK).getUrl()).isEqualTo("https://a.example/menu");
        assertThat(service.redirectResultUrl(GO, "menu", CLICK).getUrl()).isEqualTo("https://b.example/menu");
        assertThat(service.redirectResultUrl("menu", CLICK).getUrl()).isEqualTo("https://a.example/menu");
        assertThat(service.redirectResultUrl(GO, "menu", CLICK).getUrl()).isEqualTo("https://b.example/menu");

        verify(urlRepository).findServed(null, "menu");
        verify(urlRepository).findServed(7L, "menu");
        assertThat(((UrlResponse) urls.getCache("urls").get("menu").get()).id()).isEqualTo(1L);
        assertThat(((UrlResponse) urls.getCache("urls").get("7/menu").get()).id()).isEqualTo(2L);
        verify(clickAnalyticsService, times(2)).recordClick(1L, "menu", CLICK);
        verify(clickAnalyticsService, times(2)).recordClick(2L, "7/menu", CLICK);
    }

    @Test
    void evictingOneLeavesTheOther() {
        final var urls = new ConcurrentMapCacheManager("urls");
        final var service = withTheCacheAnnotations(urlServiceCachingIn(urls), urls);
        final var cache = urls.getCache("urls");
        final var onDomain = onGoExampleCom();
        cache.put("menu", UrlResponse.from(onHrva()));
        cache.put("7/menu", UrlResponse.from(onDomain));
        when(urlRepository.findById(2L)).thenReturn(Optional.of(onDomain));
        when(urlRepository.save(onDomain)).thenReturn(onDomain);

        service.revokeUrl(2L);

        assertThat(cache.get("7/menu")).isNull();
        assertThat(cache.get("menu")).isNotNull();
    }

    @Test
    void revokingTheHrvaLinkLeavesTheDomainLinkCached() {
        final var urls = new ConcurrentMapCacheManager("urls");
        final var service = withTheCacheAnnotations(urlServiceCachingIn(urls), urls);
        final var cache = urls.getCache("urls");
        final var onHrva = onHrva();
        cache.put("menu", UrlResponse.from(onHrva));
        cache.put("7/menu", UrlResponse.from(onGoExampleCom()));
        when(urlRepository.findById(1L)).thenReturn(Optional.of(onHrva));
        when(urlRepository.save(onHrva)).thenReturn(onHrva);

        service.revokeUrl(1L);

        assertThat(cache.get("menu")).isNull();
        assertThat(cache.get("7/menu")).isNotNull();
    }

    @Test
    void aNewDomainLinkIsCachedUnderItsDomainKey() {
        final var urls = new ConcurrentMapCacheManager("urls");
        final var link = onGoExampleCom();
        when(createUrlToUrlConverter.convert(any())).thenReturn(link);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(5L).email("ana@example.com").build());
        when(customDomainRepository.findUsable("go.example.com", 5L)).thenReturn(Optional.of(link.getDomain()));
        when(urlRepository.save(link)).thenReturn(link);

        urlServiceCachingIn(urls).saveUrlRouting(CreateUrlDto.builder().longUrl("https://b.example/menu").shortUrl("menu").domain("go.example.com").build(),
                CLIENT_IP, CreatedVia.WEB);

        assertThat(urls.getCache("urls").get("7/menu")).isNotNull();
        assertThat(urls.getCache("urls").get("menu")).isNull();
    }

    @Test
    void aDisabledDomainBypassesTheCache() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        final var off = new DomainRoute(7L, "go.example.com", CustomDomainStatus.DISABLED, true);

        assertThat(urlServiceWithMeters().redirectResultUrl(off, "menu", CLICK).getUrl())
                .isEqualTo(FRONTEND + "/unavailable?s=menu&reason=domain&d=go.example.com");

        verify(cacheManager, never()).getCache(any());
        verifyNoInteractions(urlRepository, clickAnalyticsService, clickCheckService);
    }

    @Test
    void aDisabledDomainOfAnInactiveOwnerSaysTheDomainIsOff() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        final var off = new DomainRoute(7L, "go.example.com", CustomDomainStatus.DISABLED, false);

        assertThat(urlServiceWithMeters().redirectResultUrl(off, "menu", CLICK).getUrl())
                .isEqualTo(FRONTEND + "/unavailable?s=menu&reason=domain&d=go.example.com");
    }

    @Test
    void anInactiveOwnersDomainIsUnavailable() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        final var ofAnInactiveOwner = new DomainRoute(7L, "go.example.com", CustomDomainStatus.ACTIVE, false);

        assertThat(urlServiceWithMeters().redirectResultUrl(ofAnInactiveOwner, "menu", CLICK).getUrl())
                .isEqualTo(FRONTEND + "/unavailable?s=menu&reason=inactive&d=go.example.com");

        verify(cacheManager, never()).getCache(any());
        verifyNoInteractions(urlRepository, clickAnalyticsService);
    }

    @Test
    void aDomainLinkWhoseDomainWasTurnedOffSinceTheLastReloadIsUnavailable() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        final var link = onGoExampleCom();
        link.getDomain().setStatus(CustomDomainStatus.DISABLED);
        when(urlRepository.findServed(7L, "menu")).thenReturn(Optional.of(link));

        assertThat(urlServiceWithMeters().redirectResultUrl(GO, "menu", CLICK).getUrl())
                .isEqualTo(FRONTEND + "/unavailable?s=menu&reason=domain&d=go.example.com");

        verifyNoInteractions(clickAnalyticsService);
    }

    @Test
    void customUnavailableAndProtectedPagesCarryTheHost() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        final var service = urlServiceWithMeters();
        final var locked = onGoExampleCom();
        locked.setPasswordHash("hash");
        final var expired = onGoExampleCom();
        expired.setShortUrl("old");
        expired.setActive(false);
        expired.setExpirationDate(LocalDateTime.now().minusDays(1));
        when(urlRepository.findServed(7L, "menu")).thenReturn(Optional.of(locked));
        when(urlRepository.findServed(7L, "old")).thenReturn(Optional.of(expired));
        when(urlRepository.findServed(7L, "nope")).thenReturn(Optional.empty());

        assertThat(service.redirectResultUrl(GO, "nope", CLICK).getUrl()).isEqualTo(FRONTEND + "/unavailable?s=nope&reason=missing&d=go.example.com");
        assertThat(service.redirectResultUrl(GO, "old", CLICK).getUrl()).isEqualTo(FRONTEND + "/unavailable?s=old&reason=expired&d=go.example.com");
        assertThat(service.redirectResultUrl(GO, "menu", CLICK).getUrl()).isEqualTo(FRONTEND + "/protected/menu?domain=go.example.com");
    }

    @Test
    void aCachedProtectedDomainLinkGoesToTheProtectedPageWithItsHost() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        final var urls = new ConcurrentMapCacheManager("urls");
        final var locked = onGoExampleCom();
        locked.setPasswordHash("hash");
        urls.getCache("urls").put("7/menu", UrlResponse.from(locked));

        assertThat(urlServiceCachingIn(urls).redirectResultUrl(GO, "menu", CLICK).getUrl()).isEqualTo(FRONTEND + "/protected/menu?domain=go.example.com");

        verifyNoInteractions(urlRepository);
    }

    @Test
    void aDomainLinkTheClickCheckRefusesSaysBlockedWithItsHost() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(urlRepository.findServed(7L, "menu")).thenReturn(Optional.of(onGoExampleCom()));
        when(clickCheckService.mayRedirect(2L, "https://b.example/menu")).thenReturn(false);

        assertThat(urlServiceWithMeters().redirectResultUrl(GO, "menu", CLICK).getUrl())
                .isEqualTo(FRONTEND + "/unavailable?s=menu&reason=blocked&d=go.example.com");
    }

    @Test
    void botProbePathsStillShortCircuitOnACustomHost() {
        for (final var probe : List.of("favicon.ico", "ROBOTS.TXT", ".env", "wp-login.php")) {
            assertThatThrownBy(() -> urlServiceWithMeters().redirectResultUrl(GO, probe, CLICK)).as(probe)
                    .isInstanceOf(UrlNotFoundException.class);
        }

        verifyNoInteractions(urlRepository, clickAnalyticsService, cacheManager);
    }

    @Test
    void theLookupsReadTheDomainsOwnLink() {
        final var onDomain = onGoExampleCom();
        final var peek = PeekUrl.builder().shortUrl("menu").domain("go.example.com").build();
        when(customDomainRegistry.active("go.example.com")).thenReturn(Optional.of(GO));
        when(urlRepository.findActive(7L, "menu")).thenReturn(Optional.of(onDomain));
        when(urlRepository.findActive(null, "menu")).thenReturn(Optional.of(onHrva()));
        when(urlToPeekUrlConverter.convert(onDomain)).thenReturn(peek);

        assertThat(urlService.revealUrlAndRecordClick("go.example.com", "menu", CLICK).longUrl()).isEqualTo("https://b.example/menu");
        assertThat(urlService.revealUrlAndRecordClick(null, "menu", CLICK).longUrl()).isEqualTo("https://a.example/menu");
        assertThat(urlService.peekUrlByShortUrl("go.example.com", "menu")).isSameAs(peek);

        verify(clickAnalyticsService).recordClick(2L, "7/menu", CLICK);
        verify(clickAnalyticsService).recordClick(1L, "menu", CLICK);
    }

    @Test
    void theRevealOfADomainLinkKeepsItsDomain() {
        when(customDomainRegistry.active("go.example.com")).thenReturn(Optional.of(GO));
        when(urlRepository.findActive(7L, "menu")).thenReturn(Optional.of(onGoExampleCom()));

        assertThat(urlService.revealUrlAndRecordClick("go.example.com", "menu", CLICK).domain())
                .isEqualTo(new DomainRef(7L, "go.example.com"));
    }

    @Test
    void aBlankDomainMeansHrva() {
        when(urlRepository.findActive(null, "menu")).thenReturn(Optional.of(onHrva()));

        assertThat(urlService.revealUrlAndRecordClick("", "menu", CLICK).id()).isEqualTo(1L);
        assertThat(urlService.revealUrlAndRecordClick(" ", "menu", CLICK).id()).isEqualTo(1L);

        verifyNoInteractions(customDomainRegistry);
    }

    @Test
    void anUnknownOrTurnedOffDomainIsNotFound() {
        when(customDomainRegistry.active("off.example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> urlService.revealUrlAndRecordClick("off.example.com", "menu", CLICK))
                .isInstanceOf(UrlNotFoundException.class).hasMessage("URL doesn't exist");
        assertThatThrownBy(() -> urlService.peekUrlByShortUrl("off.example.com", "menu"))
                .isInstanceOf(UrlNotFoundException.class).hasMessage("URL doesn't exist");
        assertThatThrownBy(() -> urlService.unlockUrl("off.example.com", "menu", "pw", CLICK))
                .isInstanceOf(UrlNotFoundException.class).hasMessage("URL doesn't exist");

        verifyNoInteractions(urlRepository, clickAnalyticsService, rateLimiter);
    }

    @Test
    void unlockOnADomainLinkUsesItsLinkAndItsOwnRateLimitKeys() {
        final var locked = onGoExampleCom();
        locked.setPasswordHash("hash");
        when(customDomainRegistry.active("go.example.com")).thenReturn(Optional.of(GO));
        when(urlRepository.findActive(7L, "menu")).thenReturn(Optional.of(locked));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        assertThat(urlService.unlockUrl("go.example.com", "menu", "pw", CLICK).longUrl()).isEqualTo("https://b.example/menu");

        verify(rateLimiter).tryAcquire(RateLimiter.clientKey("unlock:7/menu", CLIENT_IP), 10, Duration.ofMinutes(15));
        verify(rateLimiter).tryAcquire("unlock:7/menu", 50, Duration.ofMinutes(15));
        verify(rateLimiter, never()).tryAcquire(eq("unlock:menu"), anyInt(), any());
        verify(clickAnalyticsService).recordClick(2L, "7/menu", CLICK.withUnknownReferrer());
        verify(urlRepository, never()).findActive(isNull(), any());
    }

    @Test
    void aDomainLinkTheClickCheckRefusesAtUnlockSaysBlockedWithItsHost() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(customDomainRegistry.active("go.example.com")).thenReturn(Optional.of(GO));
        when(urlRepository.findActive(7L, "menu")).thenReturn(Optional.of(onGoExampleCom()));
        when(clickCheckService.mayRedirect(2L, "https://b.example/menu")).thenReturn(false);

        assertThat(urlService.unlockUrl("go.example.com", "menu", null, CLICK).longUrl())
                .isEqualTo(FRONTEND + "/unavailable?s=menu&reason=blocked&d=go.example.com");
    }

    private static Url onHrva() {
        return Url.builder().id(1L).shortUrl("menu").longUrl("https://a.example/menu").visits(0L).active(true).build();
    }

    private static Url onGoExampleCom() {
        final var domain = CustomDomain.builder().id(7L).hostname("go.example.com").status(CustomDomainStatus.ACTIVE).build();
        return Url.builder().id(2L).shortUrl("menu").longUrl("https://b.example/menu").visits(0L).active(true).domain(domain).build();
    }

    private UrlService urlServiceCachingIn(final CacheManager urls) {
        return new DefaultUrlService(userService, urlValidator, apiKeyService, urlRepository, appProperties, apiKeyValidator, clickAnalyticsService,
                urlToPeekUrlConverter, createUrlToUrlConverter, urlUpdateDtoToUrlConverter, new UrlCache(urls), objectMapper,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), passwordEncoder, suspiciousUrlService, rateLimiter,
                reviewQueueService, scammerSignalService, clickCheckService, redirectChainService, TransactionOperations.withoutTransaction(),
                reservedAliasService, burstAlertService, destinationCheckRepository, customDomainRegistry, customDomainRepository);
    }

    private static UrlService withTheCacheAnnotations(final UrlService service, final CacheManager urls) {
        final var interceptor = new CacheInterceptor();
        interceptor.setCacheOperationSource(new AnnotationCacheOperationSource());
        interceptor.setCacheManager(urls);
        interceptor.afterPropertiesSet();
        interceptor.afterSingletonsInstantiated();
        final var proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        return (UrlService) proxyFactory.getProxy();
    }

    private static Url.UrlBuilder storedLink() {
        return Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L);
    }

    private String redirectOf(final Url url) {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(urlRepository.findServed(null, url.getShortUrl())).thenReturn(Optional.of(url));
        return urlServiceWithMeters().redirectResultUrl(url.getShortUrl(), CLICK).getUrl();
    }

    private UrlService urlServiceWithMeters() {
        return new DefaultUrlService(
                userService,
                urlValidator,
                apiKeyService,
                urlRepository,
                appProperties,
                apiKeyValidator,
                clickAnalyticsService,
                urlToPeekUrlConverter,
                createUrlToUrlConverter,
                urlUpdateDtoToUrlConverter,
                new UrlCache(cacheManager),
                objectMapper,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                passwordEncoder,
                suspiciousUrlService,
                rateLimiter,
                reviewQueueService,
                scammerSignalService,
                clickCheckService,
                redirectChainService,
                TransactionOperations.withoutTransaction(),
                reservedAliasService,
                burstAlertService,
                destinationCheckRepository,
                customDomainRegistry,
                customDomainRepository
        );
    }

    private void recordTransactionDuringCheck(final String longUrl, final AtomicReference<Boolean> transactionActive) {
        when(linkCheckService.check(longUrl)).thenAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return LinkVerdict.clean();
        });
    }

    private void recordTransactionDuringBurstCheck(final AtomicReference<Boolean> transactionActive) {
        org.mockito.Mockito.doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(burstAlertService).linkCreated(any());
    }

    private void recordTransactionDuringChainCheck(final long urlId, final AtomicReference<Boolean> transactionActive) {
        org.mockito.Mockito.doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(redirectChainService).checkLater(urlId);
    }

    private static Url persisted(final Url url) {
        if (url.getId() == null) {
            url.setId(1L);
        }
        return url;
    }

    private void recordTransactionDuringSave(final AtomicReference<Boolean> transactionActive) {
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return persisted(invocation.getArgument(0));
        });
    }

    private static LinkVerdict blocked() {
        return new LinkVerdict(Outcome.BLOCK, List.of("on list HaGeZi TIF medium"), BlockReason.BLOCKLIST, "HaGeZi TIF medium");
    }

    private static User admin() {
        return User.builder().id(3L).authorities(List.of(Authorities.builder().name("ROLE_ADMIN").build())).build();
    }

    private UrlService transactionalUrlService() {
        final var transactionManager = new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:"));
        final var service = new DefaultUrlService(
                userService,
                new DefaultUrlValidator(userService, urlRepository, appProperties, safeBrowsingService, new DefaultSuspiciousUrlService(appProperties), linkCheckService,
                        reservedAliasService),
                apiKeyService,
                urlRepository,
                appProperties,
                apiKeyValidator,
                clickAnalyticsService,
                urlToPeekUrlConverter,
                new CreateUrlToUrlConverter(),
                urlUpdateDtoToUrlConverter,
                new UrlCache(cacheManager),
                objectMapper,
                meterRegistry,
                passwordEncoder,
                suspiciousUrlService,
                rateLimiter,
                reviewQueueService,
                scammerSignalService,
                clickCheckService,
                redirectChainService,
                new TransactionTemplate(transactionManager),
                reservedAliasService,
                burstAlertService,
                destinationCheckRepository,
                customDomainRegistry,
                customDomainRepository
        );
        final var proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (UrlService) proxyFactory.getProxy();
    }

    private UrlService urlServiceWithRealLongUrlHandling() {
        return new DefaultUrlService(
                userService,
                new DefaultUrlValidator(userService, urlRepository, appProperties, safeBrowsingService, new DefaultSuspiciousUrlService(appProperties), linkCheckService,
                        reservedAliasService),
                apiKeyService,
                urlRepository,
                appProperties,
                apiKeyValidator,
                clickAnalyticsService,
                urlToPeekUrlConverter,
                new CreateUrlToUrlConverter(),
                urlUpdateDtoToUrlConverter,
                new UrlCache(cacheManager),
                objectMapper,
                meterRegistry,
                passwordEncoder,
                suspiciousUrlService,
                rateLimiter,
                reviewQueueService,
                scammerSignalService,
                clickCheckService,
                redirectChainService,
                TransactionOperations.withoutTransaction(),
                reservedAliasService,
                burstAlertService,
                destinationCheckRepository,
                customDomainRegistry,
                customDomainRepository
        );
    }

    private UrlService urlServiceLimitedBy(final RateLimiter limiter) {
        return new DefaultUrlService(
                userService,
                urlValidator,
                apiKeyService,
                urlRepository,
                appProperties,
                apiKeyValidator,
                clickAnalyticsService,
                urlToPeekUrlConverter,
                createUrlToUrlConverter,
                urlUpdateDtoToUrlConverter,
                new UrlCache(cacheManager),
                objectMapper,
                meterRegistry,
                passwordEncoder,
                suspiciousUrlService,
                limiter,
                reviewQueueService,
                scammerSignalService,
                clickCheckService,
                redirectChainService,
                TransactionOperations.withoutTransaction(),
                reservedAliasService,
                burstAlertService,
                destinationCheckRepository,
                customDomainRegistry,
                customDomainRepository
        );
    }

    private void stubLinkCreation() {
        when(appProperties.getShortUrlLength()).thenReturn(8L);
        when(createUrlToUrlConverter.convert(any(CreateUrlDto.class)))
                .thenAnswer(invocation -> Url.builder().longUrl(invocation.<CreateUrlDto>getArgument(0).getLongUrl()).build());
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> persisted(invocation.getArgument(0)));
    }

    private static CreateUrlDto link(final int index) {
        return CreateUrlDto.builder().longUrl("https://example.com/" + index).build();
    }

    private static final class CountingRateLimiter implements RateLimiter {

        private final Map<String, Integer> counts = new HashMap<>();
        private final Set<Duration> windows = new HashSet<>();

        @Override
        public long increment(final String key, final Duration window) {
            windows.add(window);
            return counts.merge(key, 1, Integer::sum);
        }

    }

}
