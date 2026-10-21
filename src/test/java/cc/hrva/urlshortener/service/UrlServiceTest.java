package cc.hrva.urlshortener.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
import cc.hrva.urlshortener.exception.UrlValidationException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {

    private static final String BLOCKLIST_REFUSAL = "This destination is on a blocklist and can't be shortened. If you think that's a mistake, contact us.";
    private static final List<String> YOUNG_DOMAIN = List.of("domain registered in the last 30 days");

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

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(rateLimiter.tryAcquire(any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(urlValidator.checkIfLongUrlIsNotBlocklisted(any())).thenReturn(LinkVerdict.clean());
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
        when(urlRepository.save(url)).thenReturn(url);

        urlService.saveUrlRouting(createUrlDto);

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

        urlService.saveUrlRouting(createUrlDto);

        verify(reviewQueueService).flag(saved, YOUNG_DOMAIN);
    }

    @Test
    void aFlaggedAnonymousLinkIsSavedAndQueuedForReview() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var url = Url.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).thenReturn(new LinkVerdict(Outcome.FLAG, YOUNG_DOMAIN, null, null));
        when(appProperties.getShortUrlLength()).thenReturn(5L);
        when(urlRepository.save(url)).thenReturn(url);

        urlService.saveUrlRouting(createUrlDto);

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
        when(urlRepository.save(url)).thenReturn(url);

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
        when(urlRepository.save(url)).thenReturn(url);

        urlService.saveUrlRouting(createUrlDto);

        verify(urlValidator).checkIfUrlSafe(url);
        verify(reviewQueueService, never()).flag(any(), any());
    }

    @Test
    void aBlocklistedDestinationIsRefusedBeforeSafeBrowsingAndNeverSaved() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        when(appProperties.isAnonymousUrlCreationEnabled()).thenReturn(true);
        when(linkCheckService.check("https://njuskaio.cam/a/MAUB3")).thenReturn(blocked());

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto))
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

        transactionalUrlService().saveUrlRouting(createUrlDto);

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

        transactionalUrlService().saveUrlRouting(createUrlDto);

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
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doAnswer(invocation -> {
            queuedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(reviewQueueService).flag(any(), any());

        transactionalUrlService().saveUrlRouting(createUrlDto);

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

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto))
                .isInstanceOf(ApiException.class)
                .hasMessage("Sign in to create short URLs");

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

        final var result = urlService.saveUrlRouting(createUrlDto);

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
    void shouldSaveUrlRandomShortUrl() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("long").build();
        final var url = Url.builder().longUrl("long").build();

        when(appProperties.getShortUrlLength()).thenReturn(1L);
        when(urlRepository.save(url)).thenReturn(url);
        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);

        final var result = urlService.saveUrlRouting(createUrlDto);
        assertThat(result.longUrl()).isEqualTo("long");

        verify(urlValidator).checkIfShortUrlIsUnique(url.getShortUrl());
        verify(urlValidator).longUrlInUrl(url);
    }

    @Test
    void shouldReturnSavedUrlRandomShortUrl() {
        final var createUrlDto = CreateUrlDto.builder().longUrl("long").build();
        final var url = Url.builder().longUrl("long").build();
        final var existingLongUrl = Url.builder().longUrl("long").active(true).build();

        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(null);
        when(urlRepository.existsUrlByLongUrlAndActiveTrueAndOwnerIsNull(url.getLongUrl())).thenReturn(true);
        when(urlRepository.findByLongUrlAndActiveTrue(url.getLongUrl())).thenReturn(Optional.ofNullable(existingLongUrl));

        final var result = urlService.saveUrlRouting(createUrlDto);
        assertThat(result.longUrl()).isEqualTo("long");
        assertThat(result.active()).isTrue();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reusingAnAnonymousLinkLogsNoAliasOrDestination(final CapturedOutput output) {
        final var createUrlDto = CreateUrlDto.builder().longUrl("https://secret-destination.example/path").build();
        final var url = Url.builder().longUrl("https://secret-destination.example/path").build();
        final var existingLongUrl = Url.builder().shortUrl("secret-alias").longUrl("https://secret-destination.example/path").active(true).build();

        when(createUrlToUrlConverter.convert(createUrlDto)).thenReturn(url);
        when(userService.getUserFromToken()).thenReturn(null);
        when(urlRepository.existsUrlByLongUrlAndActiveTrueAndOwnerIsNull(url.getLongUrl())).thenReturn(true);
        when(urlRepository.findByLongUrlAndActiveTrue(url.getLongUrl())).thenReturn(Optional.of(existingLongUrl));

        final var result = urlService.saveUrlRouting(createUrlDto);

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

        when(urlRepository.save(url)).thenReturn(url);
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

        when(urlRepository.save(url)).thenReturn(url);
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
        when(urlRepository.save(url)).thenReturn(url);
        when(appProperties.getShortUrlLength()).thenReturn(1L);

        final var result = urlService.saveUrlRouting(createUrlDto);
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
        when(urlRepository.save(url)).thenReturn(url);
        when(appProperties.getShortUrlLength()).thenReturn(24L);

        final var result = urlService.saveUrlRouting(createUrlDto);

        assertThat(output)
                .contains("Saving URL for owner id=1")
                .doesNotContain("secret-destination.example")
                .doesNotContain(result.shortUrl());
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

        assertThatThrownBy(() -> service.saveUrlRouting(createUrlDto))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("That doesn't look like a valid web address. Check the URL and try again.");

        verify(safeBrowsingService, never()).checkUrlsForThreats(anyString());
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void shouldRejectMalformedLongUrlWhenSavingUrlWithApiKey() {
        final var service = urlServiceWithRealLongUrlHandling();
        final var createUrlDto = CreateUrlDto.builder().longUrl("https//wedding.hrva.cc").build();

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
        when(urlRepository.findByShortUrlAndActiveTrue("abc")).thenReturn(Optional.of(url));

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
        when(urlRepository.save(url)).thenReturn(url);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        urlServiceWithMeters().redirectResultUrl("abc", "1.2.3.4");

        assertThat(url.isActive()).isFalse();
        verify(cache).evict("abc");
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
                TransactionOperations.withoutTransaction()
        );
    }

    private void recordTransactionDuringCheck(final String longUrl, final AtomicReference<Boolean> transactionActive) {
        when(linkCheckService.check(longUrl)).thenAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return LinkVerdict.clean();
        });
    }

    private void recordTransactionDuringSave(final AtomicReference<Boolean> transactionActive) {
        when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.getArgument(0);
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
                TransactionOperations.withoutTransaction()
        );
    }

}
