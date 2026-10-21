package cc.hrva.urlshortener.service;

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
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.CreateUrlToUrlConverter;
import cc.hrva.urlshortener.converter.UrlToPeekUrlConverter;
import cc.hrva.urlshortener.converter.UrlUpdateDtoToUrlConverter;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.ApiKeyNotFoundException;
import cc.hrva.urlshortener.exception.InvalidApiKeyException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.anon.RateLimiterUnavailableException;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultSuspiciousUrlService;
import cc.hrva.urlshortener.service.impl.DefaultUrlService;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import cc.hrva.urlshortener.validator.UrlValidator;
import cc.hrva.urlshortener.validator.impl.DefaultUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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
    private static final String CREATE_LIMIT_REFUSAL = "You've created a lot of links in a short time. Try again in an hour.";

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
    private IPAddressService ipAddressService;

    @Mock
    private UrlUpdateDtoToUrlConverter urlUpdateDtoToUrlConverter;

    @Mock
    private TaskExecutor taskExecutor;

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
    private cc.hrva.urlshortener.mcp.anon.RateLimiter rateLimiter;

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
                ipAddressService,
                taskExecutor,
                urlToPeekUrlConverter,
                createUrlToUrlConverter,
                urlUpdateDtoToUrlConverter,
                cacheManager,
                objectMapper,
                meterRegistry,
                passwordEncoder,
                suspiciousUrlService,
                rateLimiter,
                reviewQueueService,
                scammerSignalService,
                clickCheckService,
                redirectChainService,
                TransactionOperations.withoutTransaction()
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
        when(urlRepository.save(changed)).thenReturn(changed);

        final var result = urlService.updateUrl(update);

        assertThat(result.longUrl()).isEqualTo("https://new.example");
        final var order = org.mockito.Mockito.inOrder(urlValidator, urlRepository);
        order.verify(urlValidator).verifyUserAdmin();
        order.verify(urlValidator).checkIfLongUrlIsValid(changed);
        order.verify(urlValidator).checkIfLongUrlIsNotAShortener(changed);
        order.verify(urlValidator).checkIfLongUrlIsNotBlocklisted(changed);
        order.verify(urlValidator).checkIfUrlSafe(changed);
        order.verify(urlRepository).save(changed);
        verify(reviewQueueService).resolveOpen(changed, ReviewResolution.DESTINATION_CHANGED, "system");
        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void aFlaggedNewDestinationIsSavedAndQueuedForReview() {
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true).build();
        final var update = UrlUpdateDto.builder().id(7L).longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var changed = Url.builder().id(7L).shortUrl("abc").longUrl("https://findfavoritedate.life/?u=f6yp608").visits(0L).active(true).build();
        when(urlRepository.findById(7L)).thenReturn(Optional.of(url));
        when(urlUpdateDtoToUrlConverter.convert(update)).thenReturn(changed);
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(changed)).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));
        when(urlRepository.save(changed)).thenReturn(changed);

        urlService.updateUrl(update);

        final var order = org.mockito.Mockito.inOrder(urlRepository, reviewQueueService);
        order.verify(urlRepository).save(changed);
        order.verify(reviewQueueService).resolveOpen(changed, ReviewResolution.DESTINATION_CHANGED, "system");
        order.verify(reviewQueueService).flag(changed, YOUNG_DOMAIN);
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
    void createChecksTheDestinationAfterTheShortenerRefusalAndBeforeSafeBrowsing() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        final var url = Url.builder().longUrl("https://example.com/menu").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(persisted(url));

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP);

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
    void aFlaggedLinkIsSavedAndQueuedForReview() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var url = Url.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var saved = Url.builder().id(9L).longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(saved);

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP);

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

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP);

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

        urlService.saveUrlWithApiKey(createUrlDto, "apikey");

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

        urlService.saveUrlRouting(createUrlDto, CLIENT_IP);

        verify(urlValidator).checkIfUrlSafe(url);
        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void aBlocklistedDestinationIsRefusedBeforeSafeBrowsingAndNeverSaved() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        when(appProperties.isAnonymousUrlCreationEnabled()).thenReturn(true);
        when(linkCheckService.check("https://njuskaio.cam/a/MAUB3")).thenReturn(blocked());

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto, CLIENT_IP))
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

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "apikey"))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(BLOCKLIST_REFUSAL);

        verify(apiKeyService, never()).apiKeyUseAction(any());
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

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP);

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

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP);

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
        when(apiKeyService.apiKeyUseAction(any())).thenAnswer(invocation -> {
            keyUsedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.getArgument(0);
        });
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        recordTransactionDuringCheck("https://example.com/menu", checkedInTransaction);
        recordTransactionDuringSave(savedInTransaction);

        transactionalUrlService().saveUrlWithApiKey(createUrlDto, "apikey");

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

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP);

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

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP);

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

        transactionalUrlService().saveUrlRouting(createUrlDto, CLIENT_IP);

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

        transactionalUrlService().saveUrlWithApiKey(createUrlDto, "apikey");

        assertThat(checkedInTransaction).hasValue(false);
    }

    @Test
    void aNewDestinationForgetsTheOldChainAndHasItsRedirectsCheckedOnceItIsCommitted() {
        final var checkedInTransaction = new AtomicReference<Boolean>();
        final var url = Url.builder().id(7L).shortUrl("abc").longUrl("https://old.example").visits(0L).active(true).build();
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
        assertThat(changed.getFinalUrl()).isNull();
        assertThat(changed.getChainCheckedAt()).isNull();
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

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP);

        assertThat(result.id()).isEqualTo(1L);
    }

    @Test
    void aRefusedLinkHasNoRedirectsChecked() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        final var url = Url.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).thenThrow(new UrlValidationException(BLOCKLIST_REFUSAL));

        assertThatThrownBy(() -> urlService.saveUrlRouting(createUrlDto, CLIENT_IP)).isInstanceOf(UrlValidationException.class);

        verify(redirectChainService, never()).checkLater(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void anUnknownApiKeyIsRefusedBeforeTheSlowLinkCheck() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(apiKeyService.fetchApiKeyByKey("junk")).thenThrow(new ApiKeyNotFoundException("Sent API key doesn't exist"));

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "junk"))
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

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "expired"))
                .isInstanceOf(InvalidApiKeyException.class)
                .hasMessage("API key has expired");

        verify(linkCheckService, never()).check(anyString());
        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(apiKeyService, never()).apiKeyUseAction(any());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void anonymousCreationWhileItIsSwitchedOffIsRefusedBeforeTheSlowLinkCheck() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto, CLIENT_IP))
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

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "apikey"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(CREATE_LIMIT_REFUSAL);

        verify(linkCheckService, never()).check(anyString());
        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(apiKeyService, never()).apiKeyUseAction(any());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void anAnonymousCreateOverTheRateLimitIsRefusedBeforeTheSlowLinkCheck() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://example.com/menu").build();
        when(rateLimiter.tryAcquire(startsWith("create:anon"), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto, CLIENT_IP))
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

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP);

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

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP);
        assertThat(result.longUrl()).isEqualTo("long");

        verify(urlValidator).checkIfShortUrlIsUnique(url.getShortUrl());
        verify(urlValidator).longUrlInUrl(url);
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

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP);
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

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP);

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
        final var apiKey = ApiKey.builder().owner(owner).build();

        when(urlRepository.save(url)).thenReturn(persisted(url));
        when(apiKeyService.fetchApiKeyByKey(api)).thenReturn(apiKey);
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(appProperties.getShortUrlLength()).thenReturn(1L);

        final var result = urlService.saveUrlWithApiKey(createUrlDto, api);
        assertThat(result).isInstanceOf(UrlResponse.class);

        verify(apiKeyValidator).apiKeyExistsByKeyAndIsValid(api);
        verify(apiKeyService).apiKeyUseAction(any(ApiKey.class));
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

        final var result = urlService.saveUrlWithApiKey(createUrlDto, api);

        assertThat(output)
                .contains("Saving URL for owner id=1")
                .doesNotContain("secret-destination.example")
                .doesNotContain(result.shortUrl());
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

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP);
        assertThat(result).isInstanceOf(UrlResponse.class);

        verify(apiKeyValidator, org.mockito.Mockito.never()).apiKeyExistsByKeyAndIsValid(any());
        verify(apiKeyService, org.mockito.Mockito.never()).apiKeyUseAction(any(ApiKey.class));
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

        final var result = urlService.saveUrlRouting(createUrlDto, CLIENT_IP);

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

        IntStream.range(0, 10).forEach(index -> service.saveUrlRouting(link(index), CLIENT_IP));

        assertThatThrownBy(() -> service.saveUrlRouting(link(10), CLIENT_IP))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(CREATE_LIMIT_REFUSAL);
        verify(urlRepository, times(10)).save(any(Url.class));

        assertThat(service.saveUrlRouting(link(11), "198.51.100.4").longUrl()).isEqualTo("https://example.com/11");
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
        final var apiKey = ApiKey.builder().owner(owner).build();
        stubLinkCreation();
        when(userService.getUserFromToken()).thenReturn(owner);
        when(apiKeyService.fetchApiKeyByKey("apikey")).thenReturn(apiKey);

        IntStream.range(0, 30).forEach(index -> service.saveUrlRouting(link(index), CLIENT_IP));
        IntStream.range(30, 60).forEach(index -> service.saveUrlWithApiKey(link(index), "apikey"));

        assertThatThrownBy(() -> service.saveUrlWithApiKey(link(60), "apikey"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(CREATE_LIMIT_REFUSAL);
        assertThatThrownBy(() -> service.saveUrlRouting(link(61), CLIENT_IP))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(CREATE_LIMIT_REFUSAL);
        verify(urlRepository, times(60)).save(any(Url.class));
        verify(apiKeyService, times(30)).apiKeyUseAction(apiKey);
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

        IntStream.range(0, 61).forEach(index -> service.saveUrlRouting(link(index), CLIENT_IP));
        IntStream.range(61, 122).forEach(index -> service.saveUrlWithApiKey(link(index), "apikey"));

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

        List.of(link(0), link(1), link(2)).forEach(dto -> service.saveUrlRouting(dto, CLIENT_IP));

        assertThat(limiter.counts).containsExactlyEntriesOf(Map.of("create:user:1", 3));
    }

    @Test
    void linksAreStillCreatedWhenTheRateLimiterIsDown() {
        stubLinkCreation();
        when(rateLimiter.tryAcquire(startsWith("create:"), anyInt(), any()))
                .thenThrow(new RateLimiterUnavailableException(new RuntimeException("down")));

        final var anonymous = urlService.saveUrlRouting(link(0), CLIENT_IP);
        when(userService.getUserFromToken()).thenReturn(User.builder().id(1L).build());
        final var signedIn = urlService.saveUrlRouting(link(1), CLIENT_IP);

        assertThat(anonymous.longUrl()).isEqualTo("https://example.com/0");
        assertThat(signedIn.longUrl()).isEqualTo("https://example.com/1");
        verify(rateLimiter).tryAcquire(RateLimiter.clientKey("create:anon", CLIENT_IP), 10, Duration.ofHours(1));
        verify(rateLimiter).tryAcquire("create:user:1", 60, Duration.ofHours(1));
        verify(urlRepository, times(2)).save(any(Url.class));
    }

    @Test
    void anonymousLinksWithoutAClientIpAreLeftToTheCallersOwnLimit() {
        stubLinkCreation();

        urlService.saveUrlRouting(link(0), null);

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
        order.verify(urlValidator).checkIfShortUrlIsFreeToReactivate("promo");
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

        assertThatThrownBy(() -> urlService.unlockUrl("board", "guess", "1.2.3.4"))
                .isInstanceOf(cc.hrva.urlshortener.exception.RateLimitExceededException.class)
                .hasMessage("Too many password attempts. Try again in a few minutes.");
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void unlockStillWorksWhenTheRateLimiterIsDown() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(rateLimiter.tryAcquire(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new cc.hrva.urlshortener.mcp.anon.RateLimiterUnavailableException(new RuntimeException("down")));
        when(urlRepository.findByShortUrlAndActiveTrue("a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);

        assertThat(urlService.unlockUrl("a", "secret", "1.2.3.4").longUrl()).isEqualTo("https://a.com");
    }

    @Test
    void shouldUnlockWithCorrectPassword() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(urlRepository.findByShortUrlAndActiveTrue("a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        final var result = urlService.unlockUrl("a", "pw", "127.0.0.1");

        assertThat(result.longUrl()).isEqualTo("https://a.com");
    }

    @Test
    void shouldRejectUnlockWithWrongPassword() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(urlRepository.findByShortUrlAndActiveTrue("a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("nope", "hash")).thenReturn(false);

        assertThatThrownBy(() -> urlService.unlockUrl("a", "nope", "127.0.0.1"))
                .isInstanceOf(cc.hrva.urlshortener.exception.NoAuthorizationException.class);
    }

    @Test
    void shouldUnlockNonProtectedUrlWithoutPasswordCheck() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).build();
        when(urlRepository.findByShortUrlAndActiveTrue("a")).thenReturn(Optional.of(url));

        final var result = urlService.unlockUrl("a", null, "127.0.0.1");

        assertThat(result.longUrl()).isEqualTo("https://a.com");
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

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto, CLIENT_IP))
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

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "apikey"))
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

        assertThatThrownBy(() -> service.saveUrlWithApiKey(createUrlDto, "apikey"))
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
        when(urlRepository.findByShortUrlAndActiveTrue("menu")).thenReturn(Optional.of(url));

        final var result = urlService.checkIPUniquenessAndReturnUrl("menu", "1.2.3.4");

        assertThat(result.longUrl()).isEqualTo("https://gableci.hrva.cc");
        assertThat(result.ownerEmail()).isNull();
        assertThat(result.tags()).isEmpty();
    }

    @Test
    void publicLookupOfALinkFoundOnABlocklistIsNotFoundAndRecordsNoVisit() {
        final var url = Url.builder().id(3L).shortUrl("menu").longUrl("https://jofogas.cards/login").active(true).build();
        when(urlRepository.findByShortUrlAndActiveTrue("menu")).thenReturn(Optional.of(url));
        when(clickCheckService.mayRedirect(3L, "https://jofogas.cards/login")).thenReturn(false);

        assertThatThrownBy(() -> urlService.checkIPUniquenessAndReturnUrl("menu", "1.2.3.4"))
                .isInstanceOf(cc.hrva.urlshortener.exception.UrlNotFoundException.class)
                .hasMessage("URL doesn't exist");

        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void peekingAtALinkFoundOnABlocklistIsNotFound() {
        final var url = Url.builder().id(3L).shortUrl("menu").longUrl("https://jofogas.cards/login").active(true).build();
        when(urlRepository.findByShortUrlAndActiveTrue("menu")).thenReturn(Optional.of(url));
        when(clickCheckService.mayRedirect(3L, "https://jofogas.cards/login")).thenReturn(false);

        assertThatThrownBy(() -> urlService.peekUrlByShortUrl("menu"))
                .isInstanceOf(cc.hrva.urlshortener.exception.UrlNotFoundException.class)
                .hasMessage("URL doesn't exist");

        verify(urlToPeekUrlConverter, never()).convert(any());
    }

    @Test
    void peekingAtALinkThatPassesTheClickCheckShowsIt() {
        final var url = Url.builder().id(3L).shortUrl("menu").longUrl("https://gableci.hrva.cc").active(true).build();
        final var peek = cc.hrva.urlshortener.model.PeekUrl.builder().shortUrl("menu").longUrl("https://gableci.hrva.cc").build();
        when(urlRepository.findByShortUrlAndActiveTrue("menu")).thenReturn(Optional.of(url));
        when(urlToPeekUrlConverter.convert(url)).thenReturn(peek);

        assertThat(urlService.peekUrlByShortUrl("menu")).isSameAs(peek);

        verify(clickCheckService).mayRedirect(3L, "https://gableci.hrva.cc");
    }

    @Test
    void lookingUpAPasswordProtectedLinkRevealsNothingSoSkipsTheClickCheck() {
        final var url = Url.builder().id(4L).shortUrl("board").longUrl("https://secret.example/board").passwordHash("$2a$10$hash").active(true).build();
        when(urlRepository.findByShortUrlAndActiveTrue("board")).thenReturn(Optional.of(url));

        urlService.checkIPUniquenessAndReturnUrl("board", "1.2.3.4");
        urlService.peekUrlByShortUrl("board");

        verify(clickCheckService, never()).mayRedirect(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void publicLookupOfAPasswordProtectedLinkRevealsNoDestinationAndRecordsNoVisit() {
        final var url = Url.builder().id(4L).shortUrl("board").longUrl("https://secret.example/board").passwordHash("$2a$10$hash").active(true).build();
        when(urlRepository.findByShortUrlAndActiveTrue("board")).thenReturn(Optional.of(url));

        final var result = urlService.checkIPUniquenessAndReturnUrl("board", "1.2.3.4");

        assertThat(result.longUrl()).isNull();
        assertThat(result.passwordProtected()).isTrue();
        verify(taskExecutor, never()).execute(any(Runnable.class));
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
        when(urlRepository.findFirstByShortUrlOrderByActiveDescIdDesc("abc")).thenReturn(Optional.of(url));

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", "1.2.3.4");

        assertThat(redirect.getUrl()).isEqualTo("https://example.com/a");
    }

    @Test
    void aVisitThatReachesTheLimitEvictsTheCachedLink() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var cached = new UrlResponse(5L, "https://example.com/a", "abc", null, null, 0L, 3L, true, "ACTIVE", java.util.Set.of(), false, null);
        when(cache.get("abc")).thenReturn(() -> cached);
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(2L).visitLimit(3L).active(true).build();
        when(urlRepository.findByShortUrlAndActiveTrue("abc")).thenReturn(Optional.of(url));
        when(urlRepository.deactivateAtVisitLimit(5L)).thenReturn(1);
        runVisitTrackingInline();

        urlServiceWithMeters().redirectResultUrl("abc", "1.2.3.4");

        verify(urlRepository).recordVisit(eq(5L), any(LocalDateTime.class));
        verify(cache).evict("abc");
        verify(urlRepository, never()).save(any());
    }

    @Test
    void aVisitBelowTheLimitKeepsTheCachedLink() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var cached = new UrlResponse(5L, "https://example.com/a", "abc", null, null, 0L, 3L, true, "ACTIVE", java.util.Set.of(), false, null);
        when(cache.get("abc")).thenReturn(() -> cached);
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).visitLimit(3L).active(true).build();
        when(urlRepository.findByShortUrlAndActiveTrue("abc")).thenReturn(Optional.of(url));
        runVisitTrackingInline();

        urlServiceWithMeters().redirectResultUrl("abc", "1.2.3.4");

        verify(urlRepository).recordVisit(eq(5L), any(LocalDateTime.class));
        verify(cache, never()).evict(any());
    }

    @Test
    void aVisitCountedAfterTheLinkWasBlockedNeverWritesTheLoadedLinkBack() {
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).build();
        when(urlRepository.findFirstByShortUrlOrderByActiveDescIdDesc("abc")).thenReturn(Optional.of(url));
        final var visitTracking = org.mockito.ArgumentCaptor.forClass(Runnable.class);

        urlServiceWithMeters().redirectResultUrl("abc", "1.2.3.4");
        verify(taskExecutor).execute(visitTracking.capture());
        url.setActive(false);
        url.setBlockReason(BlockReason.BLOCKLIST);
        visitTracking.getValue().run();

        verify(urlRepository).recordVisit(eq(5L), any(LocalDateTime.class));
        verify(urlRepository).deactivateAtVisitLimit(5L);
        verify(urlRepository, never()).save(any());
    }

    @Test
    void aCachedLinkFoundOnABlocklistAtClickTimeGoesToTheUnavailablePageWithoutCountingTheVisit() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var cached = new UrlResponse(5L, "https://jofogas.cards/login", "abc", null, null, 0L, null, true, "ACTIVE", java.util.Set.of(), false, null);
        when(cache.get("abc")).thenReturn(() -> cached);
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(clickCheckService.mayRedirect(5L, "https://jofogas.cards/login")).thenReturn(false);

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", "1.2.3.4");

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=blocked");
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void aDatabaseLinkFoundOnABlocklistAtClickTimeGoesToTheUnavailablePageAndIsNeverCached() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://jofogas.cards/login").visits(0L).active(true).build();
        when(urlRepository.findFirstByShortUrlOrderByActiveDescIdDesc("abc")).thenReturn(Optional.of(url));
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(clickCheckService.mayRedirect(5L, "https://jofogas.cards/login")).thenReturn(false);

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", "1.2.3.4");

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=abc&reason=blocked");
        verify(cache, never()).put(any(), any());
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void aDatabaseLinkThatPassesTheClickCheckIsCachedAndCounted() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).build();
        when(urlRepository.findFirstByShortUrlOrderByActiveDescIdDesc("abc")).thenReturn(Optional.of(url));

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", "1.2.3.4");

        assertThat(redirect.getUrl()).isEqualTo("https://example.com/a");
        verify(clickCheckService).mayRedirect(5L, "https://example.com/a");
        verify(cache).put(eq("abc"), any(UrlResponse.class));
        verify(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void aPasswordProtectedLinkGoesToItsGateWithoutTheClickCheck() {
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).passwordHash("hash").build();
        when(urlRepository.findFirstByShortUrlOrderByActiveDescIdDesc("abc")).thenReturn(Optional.of(url));
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        final var redirect = urlServiceWithMeters().redirectResultUrl("abc", "1.2.3.4");

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/protected/abc");
        verify(clickCheckService, never()).mayRedirect(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void unlockingALinkFoundOnABlocklistAtClickTimeSendsTheVisitorToTheUnavailablePage() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(urlRepository.findByShortUrlAndActiveTrue("a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(clickCheckService.mayRedirect(1L, "https://a.com")).thenReturn(false);

        final var result = urlService.unlockUrl("a", "pw", "127.0.0.1");

        assertThat(result.longUrl()).isEqualTo(FRONTEND + "/unavailable?s=a&reason=blocked");
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void aWrongPasswordNeverReachesTheClickCheck() {
        final var url = Url.builder().id(1L).shortUrl("a").longUrl("https://a.com").active(true).passwordHash("hash").build();
        when(urlRepository.findByShortUrlAndActiveTrue("a")).thenReturn(Optional.of(url));
        when(passwordEncoder.matches("nope", "hash")).thenReturn(false);

        assertThatThrownBy(() -> urlService.unlockUrl("a", "nope", "127.0.0.1"))
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

        final var redirect = urlServiceWithMeters().redirectResultUrl("nope", "1.2.3.4");

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=nope&reason=missing");
    }

    @Test
    void anUnknownShortLinkCostsOneQuery() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        urlServiceWithMeters().redirectResultUrl("nope", "1.2.3.4");

        verify(urlRepository).findFirstByShortUrlOrderByActiveDescIdDesc("nope");
        org.mockito.Mockito.verifyNoMoreInteractions(urlRepository);
    }

    @Test
    void anActiveLinkCostsOneQueryEvenWithAnOlderInactiveDuplicate() {
        final var cache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("urls")).thenReturn(cache);
        final var url = Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L).active(true).build();
        when(urlRepository.findFirstByShortUrlOrderByActiveDescIdDesc("abc")).thenReturn(Optional.of(url));

        assertThat(urlServiceWithMeters().redirectResultUrl("abc", "1.2.3.4").getUrl()).isEqualTo("https://example.com/a");

        verify(urlRepository).findFirstByShortUrlOrderByActiveDescIdDesc("abc");
        org.mockito.Mockito.verifyNoMoreInteractions(urlRepository);
    }

    @Test
    void aLongAbusivePathIsCutTo32CharactersOnTheUnavailablePage() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        final var redirect = urlServiceWithMeters().redirectResultUrl("a".repeat(31) + "é" + "b".repeat(8000), "1.2.3.4");

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=" + "a".repeat(31) + "%C3%A9&reason=missing");
    }

    @Test
    void theShortLinkIsUrlEncodedOnTheUnavailablePage() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        final var redirect = urlServiceWithMeters().redirectResultUrl("a b&c=d+é", "1.2.3.4");

        assertThat(redirect.getUrl()).isEqualTo(FRONTEND + "/unavailable?s=a+b%26c%3Dd%2B%C3%A9&reason=missing");
    }

    @Test
    void theRootStillGoesToTheBareFrontend() {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);

        assertThat(urlServiceWithMeters().redirectResultUrl(null, null).getUrl()).isEqualTo(FRONTEND);
    }

    @Test
    void botProbesStillGetANotFound() {
        assertThatThrownBy(() -> urlServiceWithMeters().redirectResultUrl("favicon.ico", "1.2.3.4"))
                .isInstanceOf(cc.hrva.urlshortener.exception.UrlNotFoundException.class);

        org.mockito.Mockito.verifyNoInteractions(urlRepository);
    }

    private static Url.UrlBuilder storedLink() {
        return Url.builder().id(5L).shortUrl("abc").longUrl("https://example.com/a").visits(0L);
    }

    private String redirectOf(final Url url) {
        when(appProperties.getFrontendUrl()).thenReturn(FRONTEND);
        when(urlRepository.findFirstByShortUrlOrderByActiveDescIdDesc(url.getShortUrl())).thenReturn(Optional.of(url));
        return urlServiceWithMeters().redirectResultUrl(url.getShortUrl(), "1.2.3.4").getUrl();
    }

    private void runVisitTrackingInline() {
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
    }

    private UrlService urlServiceWithMeters() {
        return new DefaultUrlService(
                userService,
                urlValidator,
                apiKeyService,
                urlRepository,
                appProperties,
                apiKeyValidator,
                ipAddressService,
                taskExecutor,
                urlToPeekUrlConverter,
                createUrlToUrlConverter,
                urlUpdateDtoToUrlConverter,
                cacheManager,
                objectMapper,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                passwordEncoder,
                suspiciousUrlService,
                rateLimiter,
                reviewQueueService,
                scammerSignalService,
                clickCheckService,
                redirectChainService,
                TransactionOperations.withoutTransaction()
        );
    }

    private void recordTransactionDuringCheck(final String longUrl, final AtomicReference<Boolean> transactionActive) {
        when(linkCheckService.check(longUrl)).thenAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return LinkVerdict.clean();
        });
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
                new DefaultUrlValidator(userService, urlRepository, appProperties, safeBrowsingService, new DefaultSuspiciousUrlService(appProperties), linkCheckService),
                apiKeyService,
                urlRepository,
                appProperties,
                apiKeyValidator,
                ipAddressService,
                taskExecutor,
                urlToPeekUrlConverter,
                new CreateUrlToUrlConverter(),
                urlUpdateDtoToUrlConverter,
                cacheManager,
                objectMapper,
                meterRegistry,
                passwordEncoder,
                suspiciousUrlService,
                rateLimiter,
                reviewQueueService,
                scammerSignalService,
                clickCheckService,
                redirectChainService,
                new TransactionTemplate(transactionManager)
        );
        final var proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (UrlService) proxyFactory.getProxy();
    }

    private UrlService urlServiceWithRealLongUrlHandling() {
        return new DefaultUrlService(
                userService,
                new DefaultUrlValidator(userService, urlRepository, appProperties, safeBrowsingService, new DefaultSuspiciousUrlService(appProperties), linkCheckService),
                apiKeyService,
                urlRepository,
                appProperties,
                apiKeyValidator,
                ipAddressService,
                taskExecutor,
                urlToPeekUrlConverter,
                new CreateUrlToUrlConverter(),
                urlUpdateDtoToUrlConverter,
                cacheManager,
                objectMapper,
                meterRegistry,
                passwordEncoder,
                suspiciousUrlService,
                rateLimiter,
                reviewQueueService,
                scammerSignalService,
                clickCheckService,
                redirectChainService,
                TransactionOperations.withoutTransaction()
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
                ipAddressService,
                taskExecutor,
                urlToPeekUrlConverter,
                createUrlToUrlConverter,
                urlUpdateDtoToUrlConverter,
                cacheManager,
                objectMapper,
                meterRegistry,
                passwordEncoder,
                suspiciousUrlService,
                limiter,
                reviewQueueService,
                scammerSignalService,
                clickCheckService,
                redirectChainService,
                TransactionOperations.withoutTransaction()
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
        public boolean tryAcquire(final String key, final int limit, final Duration window) {
            windows.add(window);
            return counts.merge(key, 1, Integer::sum) <= limit;
        }

    }

}
