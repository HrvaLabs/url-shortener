package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.controller.LinkUnavailableView;
import cc.hrva.urlshortener.controller.LinksDownView;
import cc.hrva.urlshortener.util.ApiDates;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.controller.LinkWarningView;
import cc.hrva.urlshortener.converter.CreateUrlToUrlConverter;
import cc.hrva.urlshortener.converter.UrlToPeekUrlConverter;
import cc.hrva.urlshortener.converter.UrlUpdateDtoToUrlConverter;
import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.DestinationChangeResponse;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.dto.UnlockResponse;
import cc.hrva.urlshortener.exception.DestinationNeedsConfirmationException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.LinkClicksUsedUpException;
import cc.hrva.urlshortener.exception.LinkExpiredException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.ServiceBusyException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.PeekUrl;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CreatedVia;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.model.enums.ThreatType;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.ShortUrlUniqueIndex;
import cc.hrva.urlshortener.repository.DestinationCheckRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.specification.UrlSpecification;
import cc.hrva.urlshortener.security.AdminTwoStep;
import cc.hrva.urlshortener.service.ApiKeyService;
import cc.hrva.urlshortener.service.BurstAlertService;
import cc.hrva.urlshortener.service.CampaignService;
import cc.hrva.urlshortener.service.HumanCheckService;
import cc.hrva.urlshortener.service.ClickAnalyticsService;
import cc.hrva.urlshortener.service.ClickCheckService;
import cc.hrva.urlshortener.service.DestinationChangeService;
import cc.hrva.urlshortener.service.LinkEditor;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.RedirectChainService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.SignInFormCheckService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.burst.CreatedLink;
import cc.hrva.urlshortener.service.campaign.UrlShapes;
import cc.hrva.urlshortener.service.domain.CustomDomainHostnames;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.domain.DomainRoute;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.domain.UrlCacheKeys;
import cc.hrva.urlshortener.service.inspection.LinkInspectionService;
import cc.hrva.urlshortener.service.linksignal.AnonymousLinkSignals;
import cc.hrva.urlshortener.service.linksignal.AnonymousLinkSignals.Signals;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.signin.NewAccounts;
import cc.hrva.urlshortener.util.DatabaseOutages;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.util.ForwardedQueries;
import cc.hrva.urlshortener.util.RedirectLocations;
import cc.hrva.urlshortener.util.ReviewReasons;
import cc.hrva.urlshortener.util.SortWhitelist;
import cc.hrva.urlshortener.validator.ApiKeyValidator;
import cc.hrva.urlshortener.validator.UrlValidator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;
import org.thymeleaf.TemplateEngine;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultUrlService implements UrlService {

    private static final String OWNER_EMAIL = "owner.email";
    private static final String CREATE_DATE = "createDate";
    private static final String[] LINK_SORT_FIELDS = {"id", "shortUrl", "longUrl", CREATE_DATE, "lastAccessed", "expirationDate", "visits", "visitLimit", "active"};
    private static final SortWhitelist MY_LINK_SORTS = SortWhitelist.of(LINK_SORT_FIELDS)
            .byDefault(Sort.by(Sort.Order.desc(CREATE_DATE)))
            .thenBy(Sort.Order.desc(CREATE_DATE), Sort.Order.desc("id"));
    private static final SortWhitelist ADMIN_LINK_SORTS = SortWhitelist.of(ArrayUtils.add(LINK_SORT_FIELDS, OWNER_EMAIL))
            .byDefault(Sort.by(Sort.Order.desc(CREATE_DATE)))
            .thenBy(Sort.Order.desc(CREATE_DATE), Sort.Order.desc("id"))
            .nullsLast(OWNER_EMAIL);
    private static final String SYSTEM = "system";
    private static final String REASON_BLOCKED = LinkUnavailableView.BLOCKED;
    private static final String REASON_CHECKING = LinkUnavailableView.CHECKING;
    private static final String REASON_EXPIRED = LinkUnavailableView.EXPIRED;
    private static final String REASON_LIMIT = LinkUnavailableView.LIMIT;
    private static final String REASON_INACTIVE = LinkUnavailableView.INACTIVE;
    private static final String REASON_MISSING = LinkUnavailableView.MISSING;
    private static final String REASON_DOMAIN = LinkUnavailableView.DOMAIN;
    private static final int MAX_SHORT_URL_LENGTH = 32;
    private static final int MAX_BLOCK_DETAIL_LENGTH = 255;
    private static final int UNLOCK_ATTEMPTS_PER_CLIENT = 10;
    private static final int UNLOCK_ATTEMPTS_PER_LINK = 50;
    private static final Duration UNLOCK_WINDOW = Duration.ofMinutes(15);
    private static final int CREATES_PER_USER = 60;
    private static final Duration CREATE_WINDOW = Duration.ofHours(1);
    private static final String CREATE_LIMIT_REFUSAL = "You've created a lot of links in a short time. Try again in an hour.";
    private static final String ANONYMOUS_CREATE_UNAVAILABLE = "Links without an account can't be made right now. Try again in a minute, or sign in.";
    private static final String CREATE_UNAVAILABLE = "Links can't be made right now. Try again in a minute.";
    private static final String UNLOCK_UNAVAILABLE = "Password checks are paused for a moment. Try again in a minute.";
    private static final Duration LIMITER_RETRY = Duration.ofMinutes(1);
    private static final Duration LINKS_DOWN_RETRY = Duration.ofMinutes(1);
    private static final String CLICKS_USED_UP = "This link has used all its clicks. Edit it to raise or remove its visit limit first.";
    private static final String LINK_EXPIRED = "This link has expired. Edit it to set a later expiry or remove it first.";
    private static final String BLOCKED_LINK_NOT_TURNED_ON = "This link is blocked, so saving can't turn it back on.";
    private static final String NO_AUTHORIZATION = "You don't have authorization for this action";
    private static final String WOULD_BLOCK = "The link checks would block this destination";
    private static final String WOULD_REVIEW = "The link checks would send this destination to review";
    private static final int REDIRECT_DATABASE_SECONDS = 3;
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String BLOCKED_WHILE_EDITING = "This link was blocked while you were editing it. Reload and try again.";
    private static final int MAX_GENERATED_ALIAS_ATTEMPTS = 10;
    private static final char[] GENERATED_ALIAS_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final String NO_FREE_ALIAS = "Couldn't pick a free short URL. Try again.";
    private static final String CUSTOM_ALIASES_OFF = "Custom aliases are turned off right now.";
    private static final String DOMAIN_FIELD = "domain";
    private static final String DOMAIN_NOT_ACTIVE = "This link's domain isn't active, so the link can't be turned on.";
    private static final String SIGN_IN_CHECK_COULD_NOT_RUN = "the check couldn't run";

    private static final Duration DROPPED_QUERY_LOG_INTERVAL = Duration.ofMinutes(10);
    private static final int MAX_DROPPED_QUERY_LOGS = 10_000;

    private static final Set<String> BOT_PROBE_PATHS = Set.of(
            "favicon.ico", "favicon.png", "robots.txt", "sitemap.xml",
            ".env", ".git", ".ds_store", "wp-login.php", "wp-admin",
            "xmlrpc.php", "phpinfo.php", "config.json", ".well-known");

    private final UserService userService;
    private final UrlValidator urlValidator;
    private final ApiKeyService apiKeyService;
    private final UrlRepository urlRepository;
    private final AppProperties appProperties;
    private final ApiKeyValidator apiKeyValidator;
    private final ClickAnalyticsService clickAnalyticsService;
    private final UrlToPeekUrlConverter urlToPeekUrlConverter;
    private final CreateUrlToUrlConverter createUrlToUrlConverter;
    private final UrlUpdateDtoToUrlConverter urlUpdateDtoToUrlConverter;
    private final UrlCache urlCache;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final SuspiciousUrlService suspiciousUrlService;
    private final RateLimiter rateLimiter;
    private final ReviewQueueService reviewQueueService;
    private final ScammerSignalService scammerSignalService;
    private final ClickCheckService clickCheckService;
    private final RedirectChainService redirectChainService;
    private final TransactionOperations transactionOperations;
    private final ReservedAliasService reservedAliasService;
    private final BurstAlertService burstAlertService;
    private final DestinationCheckRepository destinationCheckRepository;
    private final CustomDomainRegistry customDomainRegistry;
    private final CustomDomainRepository customDomainRepository;
    private final CampaignService campaignService;
    private final SettingsService settingsService;
    private final PlatformTransactionManager transactionManager;
    private final AnonymousLinkSignals anonymousLinkSignals;
    private final TemplateEngine templateEngine;
    private final HumanCheckService humanCheckService;
    private final SignInFormCheckService signInFormCheckService;
    private final DestinationChangeService destinationChangeService;
    private final LinkInspectionService linkInspectionService;
    private final NewAccounts newAccounts;
    private final ModerationService moderationService;
    private final Cache<Long, Boolean> droppedQueryLogs = CacheBuilder.newBuilder()
            .expireAfterWrite(DROPPED_QUERY_LOG_INTERVAL)
            .maximumSize(MAX_DROPPED_QUERY_LOGS)
            .build();

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UrlResponse saveUrlRouting(final @Valid CreateUrlDto createUrlDto, final String clientIP, final CreatedVia createdVia,
            final String humanCheckToken) {
        final var url = createUrlToUrlConverter.convert(createUrlDto);
        final var currentUser = userService.getUserFromToken();

        log.info("Creating URL authenticated={}", currentUser != null);

        if (currentUser != null) {
            precheckDomain(createUrlDto.getDomain(), currentUser);
        }
        ensureCreateAllowed(currentUser, clientIP);
        if (currentUser == null) {
            requireNoDomain(createUrlDto.getDomain());
            urlValidator.checkIfAnonymousUrlCreationEnabled();
            humanCheckService.verify(humanCheckToken);
        }
        final var verdict = validateUrl(url);
        final var heldShape = currentUser == null ? heldShapeOf(url.getLongUrl(), verdict) : Optional.<HeldShape>empty();
        final var signals = currentUser == null && heldShape.isEmpty()
                ? anonymousLinkSignals.signalsFor(url.getLongUrl(), verdict.outcome())
                : Signals.none();

        final var newWarningPageLink = new AtomicReference<Long>();
        final var created = Objects.requireNonNull(transactionOperations.execute(status -> {
            if (currentUser != null) {
                url.setDomain(resolveAndLockDomain(createUrlDto.getDomain(), currentUser));
                url.setCreatedVia(createdVia);
                applyPassword(url, createUrlDto.getPassword());
                return Optional.of(saveUrlForUser(url, currentUser, verdict));
            }
            url.setCreatedVia(createdVia.anonymous());
            applyAnonymousMaximums(url);
            return heldShape.isPresent()
                    ? holdNewAnonymousLink(url, heldShape.get())
                    : Optional.of(madeWithoutAnAccount(createUrlForAnonymousUser(url, verdict, signals), signals, newWarningPageLink));
        })).orElseGet(() -> saveNoLongerHeld(url, verdict, newWarningPageLink));
        checkRedirectsLater(created.id());
        if (currentUser != null && !AdminTwoStep.hasAdminPowers(currentUser)) {
            checkAccountSignInFormLater(created, verdict, currentUser);
        }
        reportCreated(created, CreatedLink.creatorOf(currentUser != null ? currentUser.getId() : null, clientIP), verdict);
        if (currentUser == null) {
            reportAnonymousShape(created, verdict);
            Optional.ofNullable(newWarningPageLink.get()).ifPresent(this::checkSignInFormLater);
        }
        return created;
    }

    private void ensureCreateAllowed(final User creator, final String clientIP) {
        final var limitKey = creator != null ? "create:user:" + creator.getId() : RateLimiter.clientKey("create:anon", clientIP);
        if (!createAllowed(creator, clientIP, limitKey)) {
            log.info("Link creation refused by the rate limit userId={}", creator != null ? creator.getId() : null);
            throw new RateLimitExceededException(CREATE_LIMIT_REFUSAL, rateLimiter.resetsIn(limitKey).orElse(CREATE_WINDOW));
        }
    }

    private boolean createAllowed(final User creator, final String clientIP, final String limitKey) {
        try {
            if (creator != null) {
                return isAdmin(creator) || rateLimiter.tryAcquire(limitKey, CREATES_PER_USER, CREATE_WINDOW);
            }
            return clientIP == null
                    || rateLimiter.tryAcquire(limitKey, settingsService.number(SettingKey.ANONYMOUS_LINKS_PER_HOUR), CREATE_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            if (creator == null) {
                log.warn("Create rate limit unavailable, refusing the link without an account");
                throw new ServiceBusyException(ANONYMOUS_CREATE_UNAVAILABLE, LIMITER_RETRY);
            }
            log.warn("Create rate limit unavailable, refusing the link userId={}", creator.getId());
            throw new ServiceBusyException(CREATE_UNAVAILABLE, LIMITER_RETRY);
        }
    }

    private static boolean isAdmin(final User user) {
        return user.getAuthorities() != null && user.getAuthorities().stream()
                .anyMatch(authority -> ROLE_ADMIN.equals(authority.getName()));
    }

    private Optional<String> unlockLimitReached(final String cacheKey, final String clientIP) {
        final var clientLimitKey = RateLimiter.clientKey("unlock:" + cacheKey, clientIP);
        final var linkLimitKey = "unlock:" + cacheKey;
        try {
            if (!rateLimiter.tryAcquire(clientLimitKey, UNLOCK_ATTEMPTS_PER_CLIENT, UNLOCK_WINDOW)) {
                return Optional.of(clientLimitKey);
            }
            return rateLimiter.tryAcquire(linkLimitKey, UNLOCK_ATTEMPTS_PER_LINK, UNLOCK_WINDOW) ? Optional.empty() : Optional.of(linkLimitKey);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Unlock rate limit unavailable, refusing the attempt");
            throw new ServiceBusyException(UNLOCK_UNAVAILABLE, LIMITER_RETRY);
        }
    }

    private void applyPassword(final Url url, final String rawPassword) {
        if (StringUtils.isNotBlank(rawPassword)) {
            url.setPasswordHash(passwordEncoder.encode(rawPassword));
        }
    }

    @Override
    @Transactional
    public UnlockResponse unlockUrl(final String domain, final String shortUrl, final String password, final ClickRequest click) {
        final var place = placeOf(domain, shortUrl);
        final var reachedLimit = unlockLimitReached(place.cacheKey(), click.clientIp());
        if (reachedLimit.isPresent()) {
            throw new RateLimitExceededException("Too many password attempts. Try again in a few minutes.",
                    rateLimiter.resetsIn(reachedLimit.get()).orElse(UNLOCK_WINDOW));
        }

        final var url = urlRepository.findActive(place.domainId(), shortUrl)
                .orElseThrow(() -> new UrlNotFoundException("URL doesn't exist"));

        if (url.getPasswordHash() != null && !passwordEncoder.matches(password, url.getPasswordHash())) {
            throw new NoAuthorizationException("Incorrect password");
        }
        if (refusedAsBot(url.getVisitLimit(), click)) {
            clickAnalyticsService.recordClick(url.getId(), click.withUnknownReferrer());
            throw new UrlNotFoundException("URL doesn't exist");
        }
        if (!clickCheckService.mayRedirect(url.getId(), url.getLongUrl())) {
            return new UnlockResponse(unavailablePageUrl(place, REASON_BLOCKED));
        }
        if (openRefused(place, url.getId(), url.getVisitLimit(), click)) {
            return new UnlockResponse(unavailablePageUrl(place, REASON_LIMIT));
        }

        clickAnalyticsService.recordClick(url.getId(), click.withUnknownReferrer());
        return new UnlockResponse(url.getLongUrl());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UrlResponse saveUrlWithApiKey(final @Valid CreateUrlDto createUrlDto, final String key, final CreatedVia createdVia) {
        final var url = createUrlToUrlConverter.convert(createUrlDto);

        log.info("Creating URL with API key");

        final var apiKey = apiKeyService.fetchApiKeyByKey(key);
        apiKeyValidator.apiKeyExistsByKeyAndIsValid(key);
        precheckDomain(createUrlDto.getDomain(), apiKey.getOwner());
        ensureCreateAllowed(apiKey.getOwner(), null);
        final var verdict = validateUrl(url);

        final var created = transactionOperations.execute(status -> {
            url.setDomain(resolveAndLockDomain(createUrlDto.getDomain(), apiKey.getOwner()));
            apiKeyService.requireActive(apiKey.getId());

            url.setCreatedVia(createdVia);
            applyPassword(url, createUrlDto.getPassword());
            return saveUrlForUser(url, apiKey.getOwner(), verdict);
        });
        checkRedirectsLater(created.id());
        if (!AdminTwoStep.hasAdminPowers(apiKey.getOwner())) {
            checkAccountSignInFormLater(created, verdict, apiKey.getOwner());
        }
        reportCreated(created, CreatedLink.creatorOf(apiKey.getOwner().getId(), null), verdict);
        return created;
    }

    @Override
    public void requireUsableDomains(final List<CreateUrlDto> createUrlDtos, final String apiKey) {
        final var hostnames = createUrlDtos.stream()
                .map(CreateUrlDto::getDomain)
                .filter(StringUtils::isNotBlank)
                .map(hostname -> CustomDomainHostnames.hostnameKey(hostname).orElse(hostname))
                .distinct()
                .toList();
        if (hostnames.isEmpty()) {
            return;
        }
        final var caller = callerOf(apiKey);
        hostnames.forEach(hostname -> resolveDomain(hostname, caller));
    }

    private User callerOf(final String apiKey) {
        if (apiKey == null) {
            return userService.getUserFromToken();
        }
        final var key = apiKeyService.fetchApiKeyByKey(apiKey);
        apiKeyValidator.apiKeyExistsByKeyAndIsValid(apiKey);
        return key.getOwner();
    }

    private void precheckDomain(final String hostname, final User caller) {
        if (StringUtils.isNotBlank(hostname)) {
            transactionOperations.executeWithoutResult(status -> resolveDomain(hostname, caller));
        }
    }

    private CustomDomain resolveAndLockDomain(final String hostname, final User caller) {
        final var domain = resolveDomain(hostname, caller);
        if (domain == null) {
            return null;
        }
        final var lockedStatus = customDomainRepository.findStatusForUpdate(domain.getId());
        if (!lockedStatus.map(CustomDomainStatus.ACTIVE.name()::equals).orElse(false)) {
            log.info("Link creation refused, the domain changed while the link was created userId={} domainId={} status={}", caller.getId(),
                    domain.getId(), lockedStatus.orElse(null));
            throw new FieldValidationException(DOMAIN_FIELD, UrlValidator.DOMAIN_NOT_USABLE);
        }
        return domain;
    }

    private CustomDomain resolveDomain(final String hostname, final User caller) {
        if (caller == null) {
            requireNoDomain(hostname);
            return null;
        }
        if (StringUtils.isBlank(hostname)) {
            return null;
        }
        return CustomDomainHostnames.hostnameKey(hostname)
                .flatMap(key -> customDomainRepository.findUsable(key, caller.getId()))
                .orElseThrow(() -> {
                    log.info("Link creation refused, the domain isn't one of the caller's active domains userId={}", caller.getId());
                    return new FieldValidationException(DOMAIN_FIELD, UrlValidator.DOMAIN_NOT_USABLE);
                });
    }

    private static void requireNoDomain(final String hostname) {
        if (StringUtils.isNotBlank(hostname)) {
            log.info("Link creation on a custom domain refused for an anonymous caller");
            throw new FieldValidationException(DOMAIN_FIELD, UrlValidator.DOMAIN_NOT_USABLE);
        }
    }

    private UrlResponse saveUrlForUser(final Url url, final User owner, final LinkVerdict verdict) {
        final var customAlias = StringUtils.isNotEmpty(url.getShortUrl());
        assignShortUrl(url);
        url.setOwner(owner);

        log.info("Saving URL for owner id={} domainId={}", owner.getId(), url.domainId());
        final var saved = insertNewLink(url, customAlias ? UrlValidator.SHORT_URL_TAKEN : NO_FREE_ALIAS);
        queueIfFlagged(saved, verdict);
        cacheUrlResponse(saved);
        return UrlResponse.from(saved);
    }

    private LinkVerdict validateUrl(final Url url) {
        checkDestinationShape(url);
        final var verdict = checkDestinationSafety(url);
        urlValidator.checkIfUrlExpirationDateIsInThePast(url);
        return verdict;
    }

    private void checkDestinationShape(final Url url) {
        urlValidator.longUrlInUrl(url);
        urlValidator.checkIfLongUrlIsValid(url);
        urlValidator.checkIfLongUrlIsNotAShortener(url);
    }

    private LinkVerdict checkDestinationSafety(final Url url) {
        final var verdict = urlValidator.checkIfLongUrlIsNotBlocklisted(url);
        urlValidator.checkIfUrlSafe(url);
        return verdict;
    }

    private void queueIfFlagged(final Url url, final LinkVerdict verdict) {
        if (verdict.outcome() == LinkVerdict.Outcome.FLAG) {
            reviewQueueService.flag(url, verdict.reasons());
        }
    }

    private void assignShortUrl(final Url url) {
        if (StringUtils.isEmpty(url.getShortUrl())) {
            url.setShortUrl(nextFreeShortUrl(url.domainId()));
            log.info("URL got a generated short URL");
        } else {
            if (url.domainId() == null && !settingsService.isOn(SettingKey.CUSTOM_ALIASES)) {
                throw new UrlValidationException(CUSTOM_ALIASES_OFF);
            }
            urlValidator.checkIfShortUrlIsUnique(url.domainId(), url.getShortUrl());
            urlValidator.checkIfShortUrlIsReserved(url.getShortUrl());
        }
    }

    private Url insertNewLink(final Url url, final String lostRaceRefusal) {
        try {
            final var saved = urlRepository.save(url);
            urlRepository.flush();
            return saved;
        } catch (final DataIntegrityViolationException exception) {
            if (!ShortUrlUniqueIndex.violatedBy(exception)) {
                throw exception;
            }
            log.info("Short URL was taken by a concurrent create");
            throw new ShortUrlAlreadyExistsException(lostRaceRefusal);
        }
    }

    @Override
    public String nextFreeShortUrl(final Long domainId) {
        return Stream.generate(() -> generateShortUrl(appProperties.getShortUrlLength()))
                .limit(MAX_GENERATED_ALIAS_ATTEMPTS)
                .filter(candidate -> !reservedAliasService.isReserved(candidate) && !urlRepository.aliasTaken(domainId, candidate))
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("No free short URL among {} generated candidates", MAX_GENERATED_ALIAS_ATTEMPTS);
                    return new ShortUrlAlreadyExistsException(NO_FREE_ALIAS);
                });
    }

    @Override
    public Page<UrlResponse> getAllMyUrls(final String apiKey, final Pageable pageable,
            final UrlSearchDto search) {
        final var sorted = MY_LINK_SORTS.apply(pageable);
        final var user = getUserForExport(apiKey);

        var spec = Specification.where(UrlSpecification.hasOwner(user));

        if (StringUtils.isNotEmpty(search.getSearch())) {
            spec = spec.and(UrlSpecification.search(search.getSearch()));
        }
        if (search.getActive() != null) {
            spec = spec.and(UrlSpecification.hasActive(search.getActive()));
        }
        if (search.getExpired() != null && search.getExpired()) {
            spec = spec.and(UrlSpecification.isExpired());
        }
        if (search.getDateFrom() != null) {
            spec = spec.and(UrlSpecification.createdAfter(search.getDateFrom()));
        }
        if (search.getDateTo() != null) {
            spec = spec.and(UrlSpecification.createdBefore(search.getDateTo()));
        }
        if (StringUtils.isNotEmpty(search.getTag())) {
            spec = spec.and(UrlSpecification.hasTag(search.getTag()));
        }

        return urlRepository.findAll(spec, sorted).map(UrlResponse::from);
    }

    @Override
    public List<String> getMyTags(final String apiKey) {
        final var user = getUserForExport(apiKey);
        return urlRepository.findDistinctTagsByOwner(user);
    }

    @Override
    public Page<UrlResponse> getAllUrls(final Pageable pageable, final UrlSearchDto search) {
        final var sorted = ADMIN_LINK_SORTS.apply(pageable);
        var spec = (Specification<Url>) null;

        if (StringUtils.isNotEmpty(search.getSearch())) {
            spec = UrlSpecification.searchIncludingOwner(search.getSearch());
        }
        if (search.getActive() != null) {
            spec = (spec == null) ? UrlSpecification.hasActive(search.getActive()) : spec.and(UrlSpecification.hasActive(search.getActive()));
        }
        if (Boolean.TRUE.equals(search.getAnonymous())) {
            spec = (spec == null) ? UrlSpecification.hasNoOwner() : spec.and(UrlSpecification.hasNoOwner());
        }
        if (Boolean.TRUE.equals(search.getDeadDestination())) {
            spec = (spec == null) ? UrlSpecification.hasDeadDestination() : spec.and(UrlSpecification.hasDeadDestination());
        }
        if (search.getExpired() != null && search.getExpired()) {
            spec = (spec == null) ? UrlSpecification.isExpired() : spec.and(UrlSpecification.isExpired());
        }
        if (search.getDateFrom() != null) {
            spec = (spec == null) ? UrlSpecification.createdAfter(search.getDateFrom()) : spec.and(UrlSpecification.createdAfter(search.getDateFrom()));
        }
        if (search.getDateTo() != null) {
            spec = (spec == null) ? UrlSpecification.createdBefore(search.getDateTo()) : spec.and(UrlSpecification.createdBefore(search.getDateTo()));
        }

        final var urls = spec == null ? urlRepository.findAll(sorted) : urlRepository.findAll(spec, sorted);
        final var inReview = reviewQueueService.openUrlIds(urls.map(Url::getId).getContent());
        final var possibleScammers = urls.stream().anyMatch(url -> url.getOwner() != null)
                ? scammerSignalService.possibleScammerIds()
                : Set.<Long>of();
        return urls.map(url -> UrlResponse.from(url)
                .withSuspiciousReasons(suspiciousUrlService.findReasons(url.getLongUrl()))
                .withBlockReason(url.getBlockReason())
                .withModeration(inReview.contains(url.getId()),
                        url.getOwner() != null && possibleScammers.contains(url.getOwner().getId()),
                        url.getFinalUrl()));
    }

    @Override
    @Transactional
    public UrlResponse revokeUrl(final Long id) {
        log.info("Revoke URL id={}", id);

        final var url = urlRepository.findById(id).orElseThrow(() -> new UrlNotFoundException("Url doesn't exist"));

        urlValidator.verifyUserAdminOrOwner(url);

        final var revoked = UrlResponse.from(urlRepository.save(deactivateUrl(url)));
        urlCache.evictNowAndAgainLater(revoked.cacheKey());
        return revoked;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CacheEvict(value = UrlCache.CACHE_NAME, key = "#result.cacheKey()")
    public UrlResponse activateUrl(final Long id) {
        log.info("Activate URL id={}", id);

        final var url = urlRepository.findById(id).orElseThrow(() -> new UrlNotFoundException("Url doesn't exist"));
        final var blockAtStart = BlockState.of(url);

        urlValidator.verifyUserAdminOrOwner(url);
        urlValidator.checkIfUrlNotBlocked(url);
        requireCanTurnOn(url, url.getVisitLimit(), url.getExpirationDate());
        final var verdict = checkDestinationToTurnOn(url);
        final var byAdmin = AdminTwoStep.hasAdminPowers(userService.getUserFromToken());

        final var activated = Objects.requireNonNull(transactionOperations.execute(status -> {
            final var current = reloadUnlessBlockedSince(url, blockAtStart);
            requireActiveDomain(current);
            requireClicksLeft(current, current.getVisitLimit());
            requireNotExpired(current, current.getExpirationDate());
            current.setThreatType(null);
            current.setBlockReason(null);
            current.setBlockDetail(null);
            current.setBlockedAt(null);
            final var saved = urlRepository.save(activateUrl(current));
            if (!byAdmin) {
                queueIfFlagged(saved, verdict);
            }
            return UrlResponse.from(saved);
        }));
        checkRedirectsLater(activated.id());
        if (!byAdmin) {
            checkAccountSignInFormLater(activated, verdict, url.getOwner());
        }
        return activated;
    }

    private void requireCanTurnOn(final Url link, final Long visitLimit, final LocalDateTime expirationDate) {
        requireActiveDomain(link);
        requireClicksLeft(link, visitLimit);
        requireNotExpired(link, expirationDate);
        urlValidator.checkIfShortUrlIsFreeToReactivate(link.domainId(), link.getShortUrl());
    }

    private LinkVerdict checkDestinationToTurnOn(final Url url) {
        urlValidator.checkIfLongUrlIsNotAShortener(url);
        final var verdict = urlValidator.checkIfDestinationNotBlocked(url);
        urlValidator.checkIfUrlSafe(url);
        return verdict;
    }

    @Override
    @Transactional
    public void deleteUrl(final Long id) {
        log.info("Delete URL id={}", id);

        final var url = urlRepository.findById(id).orElseThrow(() -> new UrlNotFoundException("Url doesn't exist"));

        urlValidator.verifyUserAdminOrOwner(url);

        urlRepository.delete(url);
        urlCache.evictNowAndAgainLater(UrlCacheKeys.of(url.domainId(), url.getShortUrl()));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UrlResponse updateUrl(final UrlUpdateDto updateDto, final String apiKey) {
        log.info("Update URL id={}", updateDto.getId());

        final var keyEditor = apiKey == null || userService.getUserFromToken() != null ? null : LinkEditor.withApiKey(callerOf(apiKey));
        final var existing = urlRepository.findById(updateDto.getId())
                .orElseThrow(() -> new UrlNotFoundException(String.format("Url with id %d doesn't exist", updateDto.getId())));
        final var editor = keyEditor == null ? signedInEditor(existing) : ownerWithKey(existing, keyEditor);
        final var previousLongUrl = existing.getLongUrl();
        final var blockAtStart = BlockState.of(existing);

        final var url = urlUpdateDtoToUrlConverter.convert(updateDto);
        final var destinationChanged = !Objects.equals(previousLongUrl, url.getLongUrl());
        final var turnOn = Boolean.TRUE.equals(updateDto.getActive()) && !existing.isActive();
        requireExpiryAllowed(existing, url, turnOn);
        final var verdict = destinationChanged ? validateNewDestination(existing, url, editor, Boolean.TRUE.equals(updateDto.getForce())) : LinkVerdict.clean();
        final var forced = editor.admin() && needsConfirmation(verdict);
        final var turnOnVerdict = turnOn ? checkTurnOnAfterEdit(existing, url, editor, destinationChanged) : LinkVerdict.clean();
        if (destinationChanged) {
            destinationChangeService.countChange(existing, editor);
        }

        final var updated = transactionOperations.execute(status -> {
            final var current = reloadUnlessBlockedSince(existing, blockAtStart);
            applyEdit(updateDto, url, current, destinationChanged);
            if (turnOn) {
                requireActiveDomain(current);
                requireClicksLeft(current, current.getVisitLimit());
                current.setActive(true);
            } else if (Boolean.FALSE.equals(updateDto.getActive())) {
                current.setActive(false);
            }
            final var saved = urlRepository.save(current);
            if (destinationChanged) {
                destinationCheckRepository.reset(saved.getId());
                linkInspectionService.dropFor(saved.getId());
                if (editor.admin()) {
                    reviewQueueService.resolveOpen(saved, ReviewResolution.DESTINATION_CHANGED, SYSTEM);
                } else {
                    reviewQueueService.inspectAgainIfOpen(saved);
                }
                destinationChangeService.record(saved, previousLongUrl, editor, forced);
            }
            if (!forced) {
                queueIfFlagged(saved, verdict);
            }
            if (!editor.admin()) {
                queueIfFlagged(saved, turnOnVerdict);
            }
            return UrlResponse.from(saved);
        });
        if (!destinationChanged) {
            urlCache.evictNowAndAgainLater(updated.cacheKey());
        }
        if (destinationChanged || turnOn) {
            checkRedirectsLater(updated.id());
        }
        if ((destinationChanged || turnOn) && !editor.admin()) {
            checkAccountSignInFormLater(updated, destinationChanged ? verdict : turnOnVerdict, editor.user());
        }
        if (destinationChanged) {
            reportCreated(updated, CreatedLink.creatorOf(editor.user().getId(), null), verdict);
        }
        return updated;
    }

    @Override
    public DestinationChangeResponse destinationChange(final Long id) {
        final var url = urlRepository.findById(id).orElseThrow(() -> new UrlNotFoundException("Url doesn't exist"));
        return destinationChangeService.permissionFor(url, signedInEditor(url));
    }

    private LinkEditor signedInEditor(final Url url) {
        urlValidator.verifyUserAdminOrOwner(url);
        return LinkEditor.signedIn(userService.getUserFromToken());
    }

    private static LinkEditor ownerWithKey(final Url url, final LinkEditor editor) {
        if (!editor.owns(url)) {
            log.info("Link edit with an API key refused, the link isn't the key owner's urlId={}", url.getId());
            throw new NoAuthorizationException(NO_AUTHORIZATION);
        }
        return editor;
    }

    private void requireExpiryAllowed(final Url existing, final Url edited, final boolean turnOn) {
        if (!turnOn && sameSecond(existing.getExpirationDate(), edited.getExpirationDate())) {
            return;
        }
        urlValidator.checkIfUrlExpirationDateIsInThePast(edited);
    }

    private static boolean sameSecond(final LocalDateTime stored, final LocalDateTime sent) {
        if (stored == null || sent == null) {
            return stored == sent;
        }
        return stored.truncatedTo(ChronoUnit.SECONDS).equals(sent.truncatedTo(ChronoUnit.SECONDS));
    }

    private LinkVerdict checkTurnOnAfterEdit(final Url existing, final Url edited, final LinkEditor editor, final boolean destinationChecked) {
        if (BlockState.of(existing).isBlocked()) {
            if (!editor.admin()) {
                urlValidator.checkIfUrlNotBlocked(existing);
            }
            throw new UrlValidationException(BLOCKED_LINK_NOT_TURNED_ON);
        }
        requireCanTurnOn(existing, edited.getVisitLimit(), edited.getExpirationDate());
        return destinationChecked ? LinkVerdict.clean() : checkDestinationToTurnOn(edited);
    }

    private static void requireActiveDomain(final Url url) {
        if (url.getDomain() != null && url.getDomain().getStatus() != CustomDomainStatus.ACTIVE) {
            log.info("Reactivation refused, the link's domain isn't active urlId={} domainId={} status={}",
                    url.getId(), url.domainId(), url.getDomain().getStatus());
            throw new UrlValidationException(DOMAIN_NOT_ACTIVE);
        }
    }

    private Url reloadUnlessBlockedSince(final Url loaded, final BlockState blockAtStart) {
        final var current = urlRepository.findById(loaded.getId()).orElseThrow(() -> new UrlNotFoundException("Url doesn't exist"));
        final var blockNow = BlockState.of(current);
        if (blockNow.isBlocked() && !blockNow.equals(blockAtStart)) {
            log.info("Link was blocked while it was being edited urlId={}", current.getId());
            throw new UrlValidationException(BLOCKED_WHILE_EDITING);
        }
        return current;
    }

    private static void applyEdit(final UrlUpdateDto updateDto, final Url edited, final Url current, final boolean destinationChanged) {
        if (destinationChanged) {
            current.setLongUrl(edited.getLongUrl());
            current.setFinalUrl(null);
            current.setRedirectChain(null);
            current.setChainCheckedAt(null);
            current.setDestinationCheckedAt(null);
            current.setDestinationDeadSince(null);
            current.setDestinationFailStreak(0);
            current.setDestinationLastStatus(null);
        }
        current.setVisitLimit(edited.getVisitLimit());
        current.setExpirationDate(edited.getExpirationDate());
        if (updateDto.getTags() != null) {
            current.setTags(edited.getTags());
        }
        if (updateDto.getPassword() != null) {
            current.setPasswordHash(edited.getPasswordHash());
        }
        current.verifyUrlValidity(current);
    }

    private void checkRedirectsLater(final long urlId) {
        try {
            redirectChainService.checkLater(urlId);
        } catch (final RuntimeException exception) {
            log.warn("Could not schedule the redirect check, the nightly scan picks it up urlId={}", urlId, exception);
        }
    }

    private void checkSignInFormLater(final long urlId) {
        try {
            signInFormCheckService.checkLater(urlId);
        } catch (final RuntimeException exception) {
            log.warn("Could not schedule the sign-in form check urlId={} exception={}", urlId, exception.getClass().getName());
        }
    }

    private void checkAccountSignInFormLater(final UrlResponse link, final LinkVerdict verdict, final User owner) {
        if (verdict.outcome() == LinkVerdict.Outcome.ALLOWED || !settingsService.isOn(SettingKey.SIGN_IN_FORM_ACCOUNT_LINKS)) {
            return;
        }
        try {
            signInFormCheckService.checkAccountLinkLater(link.id());
        } catch (final RejectedExecutionException exception) {
            accountCheckCouldNotRun(link, owner);
        } catch (final RuntimeException exception) {
            log.warn("Could not schedule the account sign-in form check urlId={} domain={} exception={}", link.id(), domainOf(link.longUrl()),
                    exception.getClass().getName());
        }
    }

    private void accountCheckCouldNotRun(final UrlResponse link, final User owner) {
        try {
            if (!newAccounts.isNew(owner)) {
                log.warn("Sign-in form check queue is full, the link from an account stays as it is urlId={} domain={}", link.id(),
                        domainOf(link.longUrl()));
                return;
            }
            final var held = moderationService.holdOwnedLinkForSignInForm(link.id(), link.longUrl(), SIGN_IN_CHECK_COULD_NOT_RUN,
                    ReviewReasons.SIGN_IN_CHECK_COULD_NOT_RUN);
            log.warn("Sign-in form check queue is full, the link from a new account is held urlId={} domain={} held={}", link.id(),
                    domainOf(link.longUrl()), held);
        } catch (final RuntimeException exception) {
            log.warn("Could not handle the full sign-in form check queue urlId={} domain={} exception={}", link.id(), domainOf(link.longUrl()),
                    exception.getClass().getName());
        }
    }

    private static String domainOf(final String url) {
        return DomainNames.hostOf(url).orElse("-");
    }

    private void reportCreated(final UrlResponse created, final String creator, final LinkVerdict verdict) {
        try {
            burstAlertService.linkCreated(new CreatedLink(created.id(), created.longUrl(), creator, verdict.outcome()));
        } catch (final RuntimeException exception) {
            log.warn("Could not schedule the burst check urlId={} exception={}", created.id(), exception.getClass().getName());
        }
    }

    private void reportAnonymousShape(final UrlResponse created, final LinkVerdict verdict) {
        try {
            campaignService.recordAnonymousLink(created.id(), created.longUrl(), verdict.outcome());
        } catch (final RuntimeException exception) {
            log.warn("Could not schedule the campaign check urlId={} exception={}", created.id(), exception.getClass().getName());
        }
    }

    private LinkVerdict validateNewDestination(final Url existing, final Url url, final LinkEditor editor, final boolean force) {
        destinationChangeService.requireAllowed(existing, editor);
        checkDestinationShape(url);
        if (!editor.admin()) {
            final var verdict = checkDestinationSafety(url);
            log.info("New destination passed the checks urlId={}", url.getId());
            return verdict;
        }
        final var verdict = urlValidator.destinationVerdict(url);
        if (needsConfirmation(verdict) && !force) {
            log.info("Admin's new destination needs a confirmation urlId={} outcome={}", url.getId(), verdict.outcome());
            throw new DestinationNeedsConfirmationException(confirmationOf(verdict), verdict);
        }
        return verdict;
    }

    private static boolean needsConfirmation(final LinkVerdict verdict) {
        return verdict.outcome() == LinkVerdict.Outcome.BLOCK || verdict.outcome() == LinkVerdict.Outcome.FLAG;
    }

    private static String confirmationOf(final LinkVerdict verdict) {
        final var what = verdict.outcome() == LinkVerdict.Outcome.BLOCK ? WOULD_BLOCK : WOULD_REVIEW;
        return verdict.reasons().isEmpty() ? what + "." : what + ": " + String.join(", ", verdict.reasons()) + ".";
    }

    @Override
    @Transactional
    public UrlResponse revealUrlAndRecordClick(final String domain, final String shortUrl, final ClickRequest click) {
        final var place = placeOf(domain, shortUrl);
        log.info("URL access shortUrl={} domainId={}", shortUrl, place.domainId());

        final var url = findUrlToReveal(place);

        if (url.getPasswordHash() == null) {
            if (refusedAsBot(url.getVisitLimit(), click)) {
                clickAnalyticsService.recordClick(url.getId(), click);
                throw new UrlNotFoundException("URL doesn't exist");
            }
            if (openRefused(place, url.getId(), url.getVisitLimit(), click)) {
                throw new UrlNotFoundException("URL doesn't exist");
            }
            clickAnalyticsService.recordClick(url.getId(), click);
        }
        return UrlResponse.from(url).publicView();
    }

    @Override
    public byte[] exportMyUrlsAsCsv(final String apiKey) {
        final var user = getUserForExport(apiKey);
        final var urls = user != null
                ? urlRepository.findAllByOwner(user, Pageable.unpaged()).getContent()
                : List.<Url>of();

        final var sb = new StringBuilder();
        sb.append("Short URL,Domain,Long URL,Visits,Visit Limit,Created,Expires,Active\n");
        for (final var url : urls) {
            sb.append(String.format("%s,%s,%s,%d,%d,%s,%s,%b\n",
                    url.getShortUrl(),
                    StringUtils.defaultString(ShortLinks.hostnameOf(url)),
                    escapeCsv(url.getLongUrl()),
                    url.getVisits(),
                    url.getVisitLimit() != null ? url.getVisitLimit() : 0,
                    StringUtils.defaultString(ApiDates.utc(url.getCreateDate())),
                    StringUtils.defaultString(ApiDates.utc(url.getExpirationDate())),
                    url.isActive()));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String escapeCsv(final String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private User getUserForExport(final String apiKey) {
        if (StringUtils.isNotEmpty(apiKey)) {
            apiKeyValidator.apiKeyExistsByKeyAndIsValid(apiKey);
            return apiKeyService.fetchApiKeyByKey(apiKey).getOwner();
        }
        return userService.getUserFromToken();
    }

    @Override
    public PeekUrl peekUrlByShortUrl(final String domain, final String shortUrl) {
        return urlToPeekUrlConverter.convert(findUrlToReveal(placeOf(domain, shortUrl)));
    }

    @Override
    @Transactional
    public void deactivateExpiredUrls() {
        final var ids = urlRepository.findExpiredActiveIds(LocalDateTime.now());
        if (ids.isEmpty()) {
            return;
        }
        log.info("Deactivated {} urls", urlRepository.deactivateIfActive(ids));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public View redirectResultUrl(final String shortUrl, final ClickRequest click) {
        final var place = LinkPlace.primary(shortUrl);
        if (isBotProbe(shortUrl)) {
            return unavailable(place, REASON_MISSING).asPageOnly();
        }
        return redirect(place, click);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public View redirectResultUrl(final DomainRoute route, final String alias, final ClickRequest click) {
        refuseBotProbe(alias);
        final var place = LinkPlace.on(route, alias);
        if (!route.serves()) {
            log.info("Redirect refused, the domain doesn't serve domainId={} status={} ownerActive={}", route.id(), route.status(), route.ownerActive());
            return unavailable(place, route.status() == CustomDomainStatus.ACTIVE ? REASON_INACTIVE : REASON_DOMAIN);
        }
        return redirect(place, click);
    }

    private static void refuseBotProbe(final String shortUrl) {
        if (isBotProbe(shortUrl)) {
            throw new UrlNotFoundException("URL doesn't exist");
        }
    }

    private static boolean isBotProbe(final String shortUrl) {
        return shortUrl != null && BOT_PROBE_PATHS.contains(shortUrl.toLowerCase(Locale.ROOT));
    }

    private View redirect(final LinkPlace place, final ClickRequest click) {
        try {
            return redirectOrFail(place, click);
        } catch (final ServiceBusyException exception) {
            log.warn("Redirect answered with the links down page shortUrl={} reason=busy", place.alias());
            return linksDown(place, exception.getMessage(), exception.getRetryAfter());
        } catch (final RuntimeException exception) {
            if (DatabaseOutages.isRedis(exception)) {
                log.warn("Redirect answered with the links down page shortUrl={} reason=cache exception={}", place.alias(), exception.getClass().getName());
                return linksDown(place, DatabaseOutages.CACHE_UNREACHABLE, LINKS_DOWN_RETRY);
            }
            if (!DatabaseOutages.isOutage(exception)) {
                throw exception;
            }
            log.warn("Redirect answered with the links down page shortUrl={} exception={}", place.alias(), exception.getClass().getName());
            return linksDown(place, DatabaseOutages.UNREACHABLE, LINKS_DOWN_RETRY);
        }
    }

    private View linksDown(final LinkPlace place, final String detail, final Duration retryAfter) {
        return LinksDownView.of(templateEngine, appProperties.getFrontendUrl(), appProperties.getServerUrl(), place.alias(), place.hostname(), detail, retryAfter);
    }

    private View redirectOrFail(final LinkPlace place, final ClickRequest click) {
        final var sample = Timer.start(meterRegistry);
        if (place.domainId() == null) {
            log.info("Redirect shortUrl={}", place.alias());
        } else {
            log.info("Redirect shortUrl={} domainId={}", place.alias(), place.domainId());
        }

        if (StringUtils.isNotEmpty(place.alias())) {
            final var cachedRedirect = tryCacheHit(place, click);
            if (cachedRedirect != null) {
                sample.stop(Timer.builder("redirect.duration")
                        .tag("source", "cache")
                        .register(meterRegistry));
                return cachedRedirect;
            }

            final var fromDatabase = fetchAndRedirectFromDb(place, click);
            sample.stop(Timer.builder("redirect.duration")
                    .tag("source", "db")
                    .register(meterRegistry));
            return fromDatabase;
        }

        final var frontendUrl = appProperties.getFrontendUrl();
        final var redirectView = new RedirectView();
        redirectView.setUrl(frontendUrl.endsWith("/") ? frontendUrl : frontendUrl + "/");
        sample.stop(Timer.builder("redirect.duration")
                .tag("source", "db")
                .register(meterRegistry));
        return redirectView;
    }

    private static RedirectView redirectTo(final String location) {
        final var redirectView = new RedirectView();
        redirectView.setExpandUriTemplateVariables(false);
        redirectView.setUrl(location);
        return redirectView;
    }

    private View tryCacheHit(final LinkPlace place, final ClickRequest click) {
        final var cachedValue = Optional.ofNullable(urlCache.get(place.cacheKey()))
                .map(value -> value instanceof UrlResponse ur ? ur : mapToUrlResponse(value))
                .orElse(null);
        if (cachedValue != null) {
            if (isUrlRedirectValid(cachedValue)) {
                urlCache.recordRedirectHit();
                if (cachedValue.passwordProtected()) {
                    final var gate = new RedirectView();
                    gate.setUrl(protectedPageUrl(place));
                    return gate;
                }
                if (refusedAsBot(cachedValue.visitLimit(), click)) {
                    clickAnalyticsService.recordClick(cachedValue.id(), click);
                    return unavailable(place, REASON_MISSING);
                }
                if (!clickCheckService.mayRedirect(cachedValue.id(), cachedValue.longUrl())) {
                    return unavailable(place, REASON_BLOCKED);
                }
                return open(place, cachedValue, click);
            }
            urlCache.evict(place.cacheKey());
        }
        return null;
    }

    private View fetchAndRedirectFromDb(final LinkPlace place, final ClickRequest click) {
        final var found = Objects.requireNonNull(readTransaction().execute(status -> redirectFromDb(place, click)));
        return found.link() == null ? found.answer() : open(place, found.link(), click);
    }

    private TransactionTemplate readTransaction() {
        final var template = redirectTransaction();
        template.setReadOnly(true);
        return template;
    }

    private TransactionTemplate redirectTransaction() {
        final var template = new TransactionTemplate(transactionManager);
        template.setTimeout(REDIRECT_DATABASE_SECONDS);
        return template;
    }

    private Found redirectFromDb(final LinkPlace place, final ClickRequest click) {
        final var urlOpt = urlRepository.findServed(place.domainId(), place.alias());
        if (urlOpt.isEmpty()) {
            return Found.answered(unavailable(place, REASON_MISSING));
        }
        final var url = urlOpt.get();
        final var unavailableReason = unavailableReason(url);
        if (unavailableReason.isPresent()) {
            return Found.answered(unavailable(place, unavailableReason.get()));
        }
        final var response = UrlResponse.from(url);
        if (response.passwordProtected()) {
            urlCache.recordRedirectMiss();
            urlCache.put(place.cacheKey(), response);
            return Found.answered(redirectTo(protectedPageUrl(place)));
        }
        if (refusedAsBot(url.getVisitLimit(), click)) {
            clickAnalyticsService.recordClick(url.getId(), click);
            return Found.answered(unavailable(place, REASON_MISSING));
        }
        if (!clickCheckService.mayRedirect(url.getId(), url.getLongUrl())) {
            return Found.answered(unavailable(place, REASON_BLOCKED));
        }
        urlCache.recordRedirectMiss();
        urlCache.put(place.cacheKey(), response);
        return Found.toOpen(response);
    }

    private View open(final LinkPlace place, final UrlResponse link, final ClickRequest click) {
        if (openRefused(place, link.id(), link.visitLimit(), click)) {
            return unavailable(place, REASON_LIMIT);
        }
        clickAnalyticsService.recordClick(link.id(), click);
        return destinationView(place, link, click);
    }

    private boolean openRefused(final LinkPlace place, final long urlId, final Long visitLimit, final ClickRequest click) {
        if (!hasVisitLimit(visitLimit) || click.head()) {
            return false;
        }
        final var open = Objects.requireNonNull(redirectTransaction().execute(status -> consumeOpen(urlId)));
        if (open != Open.ALLOWED) {
            urlCache.evict(place.cacheKey());
        }
        if (open == Open.REFUSED) {
            log.info("Open refused, the visit limit is used up urlId={}", urlId);
        }
        return open == Open.REFUSED;
    }

    private Open consumeOpen(final long urlId) {
        if (urlRepository.consumeOpen(urlId) == 0) {
            return Open.REFUSED;
        }
        return urlRepository.existsByIdAndActiveTrue(urlId) ? Open.ALLOWED : Open.LAST;
    }

    private View destinationView(final LinkPlace place, final UrlResponse link, final ClickRequest click) {
        final var location = redirectLocation(link.id(), withForwardedQuery(link, click));
        if (showsWarningPage(place, link)) {
            log.info("Warning page shown urlId={}", link.id());
            return new LinkWarningView(templateEngine, appProperties.getFrontendUrl(), place.alias(), link.longUrl(), location, waitsForReview(link));
        }
        return redirectTo(location);
    }

    private String withForwardedQuery(final UrlResponse link, final ClickRequest click) {
        if (link.ownerEmail() == null) {
            return link.longUrl();
        }
        if (clickCheckService.anyBlocked(ForwardedQueries.linksIn(link.longUrl(), click.query()))) {
            if (droppedQueryLogs.asMap().putIfAbsent(link.id(), Boolean.TRUE) == null) {
                log.info("Forwarded query dropped, it carries a blocked link urlId={}", link.id());
            }
            return link.longUrl();
        }
        return ForwardedQueries.appendTo(link.longUrl(), click.query());
    }

    private boolean showsWarningPage(final LinkPlace place, final UrlResponse link) {
        return place.domainId() == null && Boolean.TRUE.equals(link.warningPage()) && settingsService.isOn(SettingKey.WARNING_PAGE)
                && (!waitsForReview(link) || settingsService.isOn(SettingKey.REVIEW_WARNING_PAGE));
    }

    private static boolean waitsForReview(final UrlResponse link) {
        return Boolean.TRUE.equals(link.warningPageForReview());
    }

    private static String redirectLocation(final Long urlId, final String longUrl) {
        try {
            return RedirectLocations.normalized(longUrl);
        } catch (final IllegalArgumentException exception) {
            log.warn("Destination host could not be written in ASCII, redirecting to it as stored urlId={} reason={}", urlId, exception.getMessage());
            return longUrl;
        }
    }

    private boolean refusedAsBot(final Long visitLimit, final ClickRequest click) {
        return hasVisitLimit(visitLimit) && clickAnalyticsService.isBot(click);
    }

    private static boolean hasVisitLimit(final Long visitLimit) {
        return visitLimit != null && visitLimit > 0;
    }

    private static Optional<String> unavailableReason(final Url url) {
        if (url.getDomain() != null && url.getDomain().getStatus() != CustomDomainStatus.ACTIVE) {
            return Optional.of(REASON_DOMAIN);
        }
        if (url.getBlockReason() == BlockReason.SIGN_IN_FORM && url.getThreatType() == null) {
            return Optional.of(REASON_CHECKING);
        }
        if (url.getBlockReason() != null || url.getThreatType() != null) {
            return Optional.of(REASON_BLOCKED);
        }
        if (hasReachedVisitLimit(url.getOpens(), url.getVisitLimit())) {
            return Optional.of(REASON_LIMIT);
        }
        if (isExpired(url.getExpirationDate())) {
            return Optional.of(REASON_EXPIRED);
        }
        return url.isActive() ? Optional.empty() : Optional.of(REASON_INACTIVE);
    }

    private String protectedPageUrl(final LinkPlace place) {
        return appProperties.getFrontendUrl() + "/protected/" + place.alias() + (place.hostname() == null ? "" : "?domain=" + encoded(place.hostname()));
    }

    private LinkUnavailableView unavailable(final LinkPlace place, final String reason) {
        return LinkUnavailableView.of(templateEngine, appProperties.getFrontendUrl(), appProperties.getServerUrl(), place.alias(), place.hostname(), reason);
    }

    private String unavailablePageUrl(final LinkPlace place, final String reason) {
        return appProperties.getFrontendUrl() + "/unavailable?s=" + encoded(StringUtils.left(place.alias(), MAX_SHORT_URL_LENGTH))
                + "&reason=" + reason + (place.hostname() == null ? "" : "&d=" + encoded(place.hostname()));
    }

    private static String encoded(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private LinkPlace placeOf(final String domain, final String alias) {
        if (StringUtils.isBlank(domain)) {
            return LinkPlace.primary(alias);
        }
        return customDomainRegistry.active(domain)
                .map(route -> LinkPlace.on(route, alias))
                .orElseThrow(() -> new UrlNotFoundException("URL doesn't exist"));
    }

    private static boolean isUrlRedirectValid(final UrlResponse response) {
        return response.active()
                && !isExpired(response.expirationDate())
                && !hasReachedVisitLimit(response.opens(), response.visitLimit());
    }

    private static boolean isExpired(final LocalDateTime expirationDate) {
        return expirationDate != null && LocalDateTime.now().isAfter(expirationDate);
    }

    private static void requireClicksLeft(final Url url, final Long visitLimit) {
        if (hasReachedVisitLimit(url.getOpens(), visitLimit)) {
            log.info("Activation refused, the link used all its clicks urlId={}", url.getId());
            throw new LinkClicksUsedUpException(CLICKS_USED_UP);
        }
    }

    private static void requireNotExpired(final Url url, final LocalDateTime expirationDate) {
        if (isExpired(expirationDate)) {
            log.info("Activation refused, the link has expired urlId={}", url.getId());
            throw new LinkExpiredException(LINK_EXPIRED);
        }
    }

    private static boolean hasReachedVisitLimit(final Long opens, final Long visitLimit) {
        return hasVisitLimit(visitLimit) && opens != null && opens >= visitLimit;
    }

    @SuppressWarnings("unchecked")
    private UrlResponse mapToUrlResponse(final Object value) {
        if (value instanceof UrlResponse ur) {
            return ur;
        }
        if (value instanceof LinkedHashMap<?, ?> map) {
            try {
                return objectMapper.convertValue(map, UrlResponse.class);
            } catch (final Exception e) {
                log.warn("Failed to convert cached value to UrlResponse", e);
                return null;
            }
        }
        return null;
    }

    private void cacheUrlResponse(final Url url) {
        final var response = UrlResponse.from(url);
        urlCache.put(response.cacheKey(), response);
    }

    @Override
    public String generateShortUrl(final Long length) {
        return RandomStringUtils.secureStrong().next(Math.toIntExact(length), GENERATED_ALIAS_CHARACTERS);
    }

    private Url findUrlToReveal(final LinkPlace place) {
        final var url = urlRepository.findActive(place.domainId(), place.alias()).orElseThrow(() -> new UrlNotFoundException("URL doesn't exist"));
        if (url.getPasswordHash() == null && !clickCheckService.mayRedirect(url.getId(), url.getLongUrl())) {
            throw new UrlNotFoundException("URL doesn't exist");
        }
        return url;
    }

    private Optional<HeldShape> heldShapeOf(final String longUrl, final LinkVerdict verdict) {
        if (verdict.outcome() == LinkVerdict.Outcome.ALLOWED) {
            return Optional.empty();
        }
        final var host = DomainNames.hostOf(longUrl).filter(candidate -> !DomainNames.isIpLiteral(candidate));
        final var shape = UrlShapes.of(longUrl);
        if (host.isEmpty() || shape.isEmpty()) {
            return Optional.empty();
        }
        try {
            return campaignService.holdingCampaignId(shape.get())
                    .map(campaignId -> new HeldShape(campaignId, shape.get().display(), DomainNames.registrableDomain(host.get())));
        } catch (final RuntimeException exception) {
            log.warn("Campaign hold check skipped exception={}", exception.getClass().getName());
            return Optional.empty();
        }
    }

    private Optional<UrlResponse> holdNewAnonymousLink(final Url url, final HeldShape held) {
        if (!campaignService.confirmHold(held.campaignId())) {
            log.info("Campaign stopped holding before the link was saved, saving it as usual campaignId={}", held.campaignId());
            return Optional.empty();
        }
        url.clearForAnonymousUser();
        url.setShortUrl(nextFreeShortUrl(null));
        url.setActive(false);
        url.setBlockReason(BlockReason.CAMPAIGN);
        url.setBlockDetail(StringUtils.abbreviate(held.shape(), MAX_BLOCK_DETAIL_LENGTH));
        url.setBlockedAt(LocalDateTime.now());
        final var saved = insertNewLink(url, NO_FREE_ALIAS);
        campaignService.addHeldLink(held.campaignId(), saved.getId(), held.domain());
        log.info("New link held for a campaign urlId={} campaignId={}", saved.getId(), held.campaignId());
        return Optional.of(UrlResponse.from(saved).asCreated());
    }

    private UrlResponse saveNoLongerHeld(final Url url, final LinkVerdict verdict, final AtomicReference<Long> newWarningPageLink) {
        final var signals = anonymousLinkSignals.signalsFor(url.getLongUrl(), verdict.outcome());
        return Objects.requireNonNull(transactionOperations.execute(status ->
                madeWithoutAnAccount(createUrlForAnonymousUser(url, verdict, signals), signals, newWarningPageLink)));
    }

    private static UrlResponse madeWithoutAnAccount(final AnonymousLink made, final Signals signals, final AtomicReference<Long> newWarningPageLink) {
        if (made.isNew() && signals.warningPage()) {
            newWarningPageLink.set(made.url().getId());
        }
        return UrlResponse.from(made.url());
    }

    private AnonymousLink createUrlForAnonymousUser(final Url url, final LinkVerdict verdict, final Signals signals) {
        url.clearForAnonymousUser();

        final var equivalent = equivalentAnonymousLink(url, signals.warningPage());
        if (equivalent.isPresent()) {
            final var existingLongUrl = equivalent.get();
            log.warn("Long url already exists in DB, will return the existing anonymous URL");
            queueIfFlagged(existingLongUrl, verdict, signals);
            cacheUrlResponse(existingLongUrl);
            return new AnonymousLink(existingLongUrl, false);
        }

        url.setShortUrl(nextFreeShortUrl(null));
        url.setWarningPage(signals.warningPage());

        log.info("Saving URL warningPage={}", signals.warningPage());
        final var saved = insertNewLink(url, NO_FREE_ALIAS);
        queueIfFlagged(saved, verdict, signals);
        cacheUrlResponse(saved);
        return new AnonymousLink(saved, true);
    }

    private void applyAnonymousMaximums(final Url url) {
        final var expiryDays = settingsService.optionalNumber(SettingKey.ANONYMOUS_EXPIRY_DAYS);
        if (expiryDays.isPresent()) {
            final var latest = LocalDateTime.now().plusDays(expiryDays.getAsInt());
            final var requested = url.getExpirationDate();
            url.setExpirationDate(requested == null || requested.isAfter(latest) ? latest : requested);
        }
        final var visitLimit = settingsService.optionalNumber(SettingKey.ANONYMOUS_VISIT_LIMIT);
        if (visitLimit.isPresent()) {
            final long highest = visitLimit.getAsInt();
            final var requested = url.getVisitLimit();
            url.setVisitLimit(requested == null || requested <= 0 ? highest : Math.min(requested, highest));
        }
    }

    private Optional<Url> equivalentAnonymousLink(final Url url, final boolean warningPage) {
        return urlRepository.findByLongUrlAndActiveTrueAndOwnerIsNull(url.getLongUrl()).stream()
                .filter(candidate -> candidate.isWarningPage() == warningPage || candidate.isWarningPageForReview())
                .filter(candidate -> Objects.equals(candidate.getExpirationDate(), url.getExpirationDate()))
                .filter(candidate -> Objects.equals(candidate.getVisitLimit(), url.getVisitLimit()))
                .filter(candidate -> !isExpired(candidate.getExpirationDate()) && !hasReachedVisitLimit(candidate.getOpens(), candidate.getVisitLimit()))
                .findFirst();
    }

    private void queueIfFlagged(final Url url, final LinkVerdict verdict, final Signals signals) {
        final var randomLooking = signals.randomLooking() && settingsService.isOn(SettingKey.RANDOM_DOMAIN_REVIEW);
        if (verdict.outcome() != LinkVerdict.Outcome.FLAG && !randomLooking) {
            return;
        }
        final var reasons = new ArrayList<String>();
        if (verdict.outcome() == LinkVerdict.Outcome.FLAG) {
            reasons.addAll(verdict.reasons());
        }
        if (randomLooking) {
            reasons.add(AnonymousLinkSignals.RANDOM_LOOKING_DOMAIN);
            log.info("Link without an account to a random-looking domain sent to review urlId={}", url.getId());
        }
        reviewQueueService.flag(url, reasons);
    }

    public Url deactivateUrl(final Url url) {
        url.setActive(false);
        return url;
    }

    public Url activateUrl(final Url url) {
        url.setActive(true);
        return url;
    }

    private record AnonymousLink(Url url, boolean isNew) {}

    private record LinkPlace(Long domainId, String hostname, String alias) {

        private static LinkPlace primary(final String alias) {
            return new LinkPlace(null, null, alias);
        }

        private static LinkPlace on(final DomainRoute route, final String alias) {
            return new LinkPlace(route.id(), route.hostname(), alias);
        }

        private String cacheKey() {
            return UrlCacheKeys.of(domainId, alias);
        }

    }

    private record HeldShape(long campaignId, String shape, String domain) {}

    private enum Open { ALLOWED, LAST, REFUSED }

    private record Found(View answer, UrlResponse link) {

        private static Found answered(final View answer) {
            return new Found(answer, null);
        }

        private static Found toOpen(final UrlResponse link) {
            return new Found(null, link);
        }

    }

    private record BlockState(BlockReason reason, ThreatType threatType, LocalDateTime blockedAt) {

        private static BlockState of(final Url url) {
            return new BlockState(url.getBlockReason(), url.getThreatType(), url.getBlockedAt());
        }

        private boolean isBlocked() {
            return reason != null || threatType != null;
        }

    }

}
