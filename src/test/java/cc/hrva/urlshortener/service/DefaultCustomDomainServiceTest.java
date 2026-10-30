package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.domain.CustomDomainApplyDto;
import cc.hrva.urlshortener.dto.domain.CustomDomainResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.CustomDomainCapacityException;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.FeatureUnavailableException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.CustomDomainAllowance;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainApplied;
import cc.hrva.urlshortener.service.domain.CustomDomainChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainHostnames;
import cc.hrva.urlshortener.service.domain.CustomDomainLifecycle;
import cc.hrva.urlshortener.service.domain.CustomDomainLimits;
import cc.hrva.urlshortener.service.domain.CustomDomainProvisioner;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval.Actor;
import cc.hrva.urlshortener.service.domain.CustomDomainResponses;
import cc.hrva.urlshortener.service.domain.CustomDomainSetup;
import cc.hrva.urlshortener.service.impl.DefaultCustomDomainService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
class DefaultCustomDomainServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-29T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final String APPLY_KEY = "domain-apply:user:7";
    private static final Duration APPLY_WINDOW = Duration.ofHours(24);
    private static final String REASON = "Menu links for my cafe";
    private static final String RESERVED = "This domain can't be used on hrva.cc.";
    private static final String TAKEN = "This domain has already been applied for.";
    private static final String OVER_ONE = "You can have 1 custom domain. Remove one to apply for another.";
    private static final String OVER_THREE = "You can have 3 custom domains. Remove one to apply for another.";
    private static final String TOO_MANY_APPLICATIONS = "You've applied for a lot of domains today. Try again tomorrow.";
    private static final String TURNED_OFF = "Custom domains aren't available right now.";
    private static final String NOT_FOUND = "Custom domain not found";
    private static final String PUNYCODE_FLAG = "International name (punycode): check it doesn't imitate another domain";

    @Mock
    private UserService userService;

    @Mock
    private CustomDomainRepository customDomainRepository;

    @Mock
    private CustomDomainAllowanceRepository customDomainAllowanceRepository;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private LinkCheckService linkCheckService;

    @Mock
    private AccountLock accountLock;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CloudflareClient cloudflareClient;

    @Mock
    private CustomDomainLifecycle customDomainLifecycle;

    @Mock
    private CustomDomainProvisioner customDomainProvisioner;

    @Mock
    private CustomDomainSetup customDomainSetup;

    @Mock
    private CustomDomainRemoval customDomainRemoval;

    @Captor
    private ArgumentCaptor<CustomDomain> saved;

    @Captor
    private ArgumentCaptor<Object> published;

    private final User ana = User.builder().id(7L).email("ana@example.com").active(true).build();
    private AppProperties appProperties;
    private CustomDomainService customDomainService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("https://app.hrva.cc");
        final var clock = Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
        lenient().when(userService.getUserFromToken()).thenReturn(ana);
        lenient().when(rateLimiter.tryAcquire(anyString(), eq(5), any(Duration.class))).thenReturn(true);
        lenient().when(linkCheckService.check(anyString())).thenReturn(LinkVerdict.clean());
        lenient().when(accountLock.lock(ana)).thenReturn(ana);
        lenient().when(customDomainRepository.findByHostnameKey(anyString())).thenReturn(Optional.empty());
        lenient().when(customDomainAllowanceRepository.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(customDomainRepository.saveAndFlush(any(CustomDomain.class))).thenAnswer(invocation -> {
            final CustomDomain domain = invocation.getArgument(0);
            domain.setId(12L);
            return domain;
        });
        customDomainService = new DefaultCustomDomainService(userService, customDomainRepository, urlRepository,
                new CustomDomainHostnames(appProperties), new CustomDomainLimits(customDomainRepository, customDomainAllowanceRepository, appProperties),
                new CustomDomainResponses(appProperties), customDomainLifecycle, customDomainProvisioner, customDomainSetup, customDomainRemoval,
                linkCheckService, accountLock, rateLimiter, eventPublisher, TransactionOperations.withoutTransaction(), appProperties, clock);
    }

    @Test
    void applyStoresANormalizedPendingApplication() {
        final var response = customDomainService.apply(apply("Go.Example.com."));

        verify(customDomainRepository).saveAndFlush(saved.capture());
        final var domain = saved.getValue();
        assertThat(domain.getHostname()).isEqualTo("go.example.com");
        assertThat(domain.getHostnameKey()).isEqualTo("go.example.com");
        assertThat(domain.getStatus()).isEqualTo(CustomDomainStatus.PENDING_REVIEW);
        assertThat(domain.getReason()).isEqualTo(REASON);
        assertThat(domain.getOwner().getId()).isEqualTo(7L);
        assertThat(domain.getApplyFlags()).isEmpty();
        assertThat(domain.getCreatedAt()).isEqualTo(NOW);
        assertThat(domain.getUpdatedAt()).isEqualTo(NOW);
        assertThat(domain.getCheckCount()).isZero();
        assertThat(domain.getCloudflareId()).isNull();
        verify(linkCheckService).check("https://go.example.com/");
        assertThat(response).isEqualTo(new CustomDomainResponse(12L, "go.example.com", CustomDomainStatus.PENDING_REVIEW, REASON, null,
                NOW, null, null, null, null, null, null, null, null, 0L));
    }

    @Test
    void theReasonIsTrimmed() {
        customDomainService.apply(CustomDomainApplyDto.builder().hostname("go.example.com").reason("  " + REASON + "\n").build());

        verify(customDomainRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo(REASON);
    }

    @Test
    void applyAlertsTheAdminAndAnnouncesTheChange() {
        customDomainService.apply(apply("go.example.com"));

        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues()).containsExactly(
                new DomainApplied(12L, "go.example.com", 7L, List.of()),
                new CustomDomainChangedEvent(12L));
    }

    @Test
    void applyTakesTheAccountLock() {
        customDomainService.apply(apply("go.example.com"));

        final var order = inOrder(accountLock, customDomainRepository);
        order.verify(accountLock).lock(ana);
        order.verify(customDomainRepository).countByOwnerIdAndStatusIn(7L, CustomDomainStatus.COUNTS_FOR_USER);
        order.verify(customDomainRepository).saveAndFlush(any(CustomDomain.class));
    }

    @Test
    void theSecondApplicationIsOverTheDefaultLimit() {
        when(customDomainRepository.countByOwnerIdAndStatusIn(7L, CustomDomainStatus.COUNTS_FOR_USER)).thenReturn(1L);

        assertThatThrownBy(() -> customDomainService.apply(apply("go.example.com")))
                .isInstanceOf(ApiException.class)
                .hasMessage(OVER_ONE);
        verify(customDomainRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void anAllowanceRaisesTheLimit() {
        when(customDomainAllowanceRepository.findById(7L))
                .thenReturn(Optional.of(CustomDomainAllowance.builder().userId(7L).maxDomains(3).reason("Agency").build()));
        when(customDomainRepository.countByOwnerIdAndStatusIn(7L, CustomDomainStatus.COUNTS_FOR_USER)).thenReturn(2L, 2L, 3L);

        customDomainService.apply(apply("one.example.com"));

        assertThatThrownBy(() -> customDomainService.apply(apply("two.example.com")))
                .isInstanceOf(ApiException.class)
                .hasMessage(OVER_THREE);
        verify(customDomainRepository).saveAndFlush(any());
    }

    @Test
    void blockedHostnamesAreRefusedLikeReservedOnes() {
        when(linkCheckService.check("https://go.evil.example.com/"))
                .thenReturn(new LinkVerdict(LinkVerdict.Outcome.BLOCK, List.of("on HaGeZi TIF"), BlockReason.BLOCKLIST, "HaGeZi TIF"));

        assertThatThrownBy(() -> customDomainService.apply(apply("go.evil.example.com")))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        exception -> assertThat(exception.getFieldErrors()).isEqualTo(Map.of("hostname", RESERVED)));
        verify(customDomainRepository, never()).saveAndFlush(any());
        verifyNoInteractions(accountLock, eventPublisher);
    }

    @Test
    void reservedHostnamesAreRefusedBeforeTheLinkCheck() {
        assertThatThrownBy(() -> customDomainService.apply(apply("go.hrva.cc")))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        exception -> assertThat(exception.getFieldErrors()).isEqualTo(Map.of("hostname", RESERVED)));
        verifyNoInteractions(linkCheckService, accountLock, eventPublisher);
    }

    @Test
    void aLookAlikeOfAReservedNameIsJustAnotherDomain() {
        customDomainService.apply(apply("hrva.cc.evil.com"));

        verify(customDomainRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getHostname()).isEqualTo("hrva.cc.evil.com");
    }

    @Test
    void flaggedHostnamesAreAcceptedWithTheirFlags() {
        when(linkCheckService.check("https://go.example.com/"))
                .thenReturn(new LinkVerdict(LinkVerdict.Outcome.FLAG, List.of("Registered 3 days ago", "imitates paypal"), null, null));

        customDomainService.apply(apply("go.example.com"));

        verify(customDomainRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getApplyFlags()).containsExactly("Registered 3 days ago", "imitates paypal");
        verify(eventPublisher).publishEvent(new DomainApplied(12L, "go.example.com", 7L, List.of("Registered 3 days ago", "imitates paypal")));
    }

    @Test
    void punycodeHostnamesAreFlaggedForTheReviewer() {
        when(linkCheckService.check("https://xn--g-1tb.example.com/"))
                .thenReturn(new LinkVerdict(LinkVerdict.Outcome.FLAG, List.of("Registered 3 days ago"), null, null));

        customDomainService.apply(apply("xn--g-1tb.example.com"));

        verify(customDomainRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getApplyFlags()).containsExactly(PUNYCODE_FLAG, "Registered 3 days ago");
        verify(eventPublisher).publishEvent(new DomainApplied(12L, "xn--g-1tb.example.com", 7L, List.of(PUNYCODE_FLAG, "Registered 3 days ago")));
    }

    @Test
    void aPunycodeLabelDeeperInTheNameIsFlaggedToo() {
        customDomainService.apply(apply("go.xn--hrv-8cd.cc"));

        verify(customDomainRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getApplyFlags()).containsExactly(PUNYCODE_FLAG);
    }

    @Test
    void flagsThatWouldOverflowTheColumnAreLeftOut() {
        when(linkCheckService.check("https://go.example.com/"))
                .thenReturn(new LinkVerdict(LinkVerdict.Outcome.FLAG, List.of("a".repeat(600), "b".repeat(500), "c".repeat(300)), null, null));

        customDomainService.apply(apply("go.example.com"));

        verify(customDomainRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getApplyFlags()).containsExactly("a".repeat(600), "c".repeat(300));
        assertThat(String.join("\n", saved.getValue().getApplyFlags())).hasSizeLessThanOrEqualTo(1000);
    }

    @Test
    void takenHostnameIs409() {
        when(customDomainRepository.findByHostnameKey("go.example.com"))
                .thenReturn(Optional.of(CustomDomain.builder().id(3L).hostname("go.example.com").build()));

        assertThatThrownBy(() -> customDomainService.apply(apply("GO.example.com")))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage(TAKEN);
        verify(customDomainRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aLostRaceOnTheHostnameIsThe409Too() {
        when(customDomainRepository.saveAndFlush(any(CustomDomain.class))).thenThrow(violation("UX_CUSTOM_DOMAIN_HOSTNAME_KEY"));

        assertThatThrownBy(() -> customDomainService.apply(apply("go.example.com")))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage(TAKEN);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aLostRaceSeenOnlyByTheRereadIsThe409Too() {
        when(customDomainRepository.saveAndFlush(any(CustomDomain.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(customDomainRepository.findByHostnameKey("go.example.com"))
                .thenReturn(Optional.empty(), Optional.of(CustomDomain.builder().id(3L).build()));

        assertThatThrownBy(() -> customDomainService.apply(apply("go.example.com")))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage(TAKEN);
    }

    @Test
    void anotherIntegrityViolationIsNotHidden() {
        final var violation = violation("fk_custom_domain_owner");
        when(customDomainRepository.saveAndFlush(any(CustomDomain.class))).thenThrow(violation);

        assertThatThrownBy(() -> customDomainService.apply(apply("go.example.com"))).isSameAs(violation);
    }

    @Test
    void aRefusedApplicationCostsNoApplicationFromTheDailyLimit() {
        assertThatThrownBy(() -> customDomainService.apply(apply("example.com"))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> customDomainService.apply(apply("-go.example.com"))).isInstanceOf(FieldValidationException.class);
        when(customDomainRepository.countByOwnerIdAndStatusIn(7L, CustomDomainStatus.COUNTS_FOR_USER)).thenReturn(1L);
        assertThatThrownBy(() -> customDomainService.apply(apply("go.example.com"))).isInstanceOf(ApiException.class).hasMessage(OVER_ONE);

        verifyNoInteractions(rateLimiter, linkCheckService, accountLock);
    }

    @Test
    void overTheRateLimitIs429() {
        when(rateLimiter.tryAcquire(APPLY_KEY, 5, APPLY_WINDOW)).thenReturn(false);

        assertThatThrownBy(() -> customDomainService.apply(apply("go.example.com")))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage(TOO_MANY_APPLICATIONS);
        verifyNoInteractions(linkCheckService, accountLock, eventPublisher);
        verify(customDomainRepository, never()).saveAndFlush(any());
    }

    @Test
    void limiterDownAllows() {
        when(rateLimiter.tryAcquire(APPLY_KEY, 5, APPLY_WINDOW)).thenThrow(new RateLimiterUnavailableException(new IllegalStateException("redis down")));

        customDomainService.apply(apply("go.example.com"));

        verify(customDomainRepository).saveAndFlush(any());
    }

    @Test
    void turnedOffIs503() {
        appProperties.getDomains().setEnabled(false);

        assertThatThrownBy(() -> customDomainService.apply(apply("go.example.com")))
                .isInstanceOf(FeatureUnavailableException.class)
                .hasMessage(TURNED_OFF);
        verifyNoInteractions(rateLimiter, linkCheckService, accountLock, customDomainRepository, eventPublisher);
    }

    @Test
    void deactivatedAccountsCannotApply() {
        ana.setActive(false);

        assertThatThrownBy(() -> customDomainService.apply(apply("go.example.com"))).isInstanceOf(DisabledException.class);
        verifyNoInteractions(rateLimiter, linkCheckService, customDomainRepository, eventPublisher);
    }

    @Test
    void anAccountDeactivatedDuringTheLinkCheckCannotApply() {
        final var deactivated = User.builder().id(7L).email("ana@example.com").active(false).build();
        when(accountLock.lock(ana)).thenReturn(deactivated);

        assertThatThrownBy(() -> customDomainService.apply(apply("go.example.com"))).isInstanceOf(DisabledException.class);
        verify(customDomainRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void noCloudflareCallOnApply() {
        customDomainService.apply(apply("go.example.com"));

        verifyNoInteractions(cloudflareClient);
    }

    @Test
    void withdrawingAPendingApplicationGoesThroughTheRemoval() {
        when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(domain(12L, CustomDomainStatus.PENDING_REVIEW, NOW)));
        when(customDomainRemoval.remove(12L, DomainLinkMode.MOVE, new Actor("ana@example.com", false))).thenReturn(DomainRemovalResponse.nothing());

        final var response = customDomainService.remove(12L, DomainLinkMode.MOVE);

        assertThat(response).isEqualTo(new DomainRemovalResponse(0, List.of(), 0));
        verifyNoInteractions(cloudflareClient);
    }

    @Test
    void everyStatusOfTheOwnersDomainCanBeRemoved() {
        final var summary = new DomainRemovalResponse(2, List.of(new DomainRemovalResponse.DomainRename("menu", "k3J9x")), 0);
        for (final var status : List.of(CustomDomainStatus.APPROVED_PROVISIONING, CustomDomainStatus.AWAITING_DNS, CustomDomainStatus.ACTIVE,
                CustomDomainStatus.DISABLED, CustomDomainStatus.ERROR, CustomDomainStatus.REJECTED)) {
            when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(domain(12L, status, NOW)));
            when(customDomainRemoval.remove(12L, DomainLinkMode.DEACTIVATE, new Actor("ana@example.com", false))).thenReturn(summary);

            assertThat(customDomainService.remove(12L, DomainLinkMode.DEACTIVATE)).isEqualTo(summary);
        }
        verify(customDomainRepository, never()).markDeleted(anyLong(), any(), any());
        verifyNoInteractions(cloudflareClient);
    }

    @Test
    void removingSomeoneElsesOrADeletedDomainIsNotFound() {
        when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.empty());
        when(customDomainRepository.findByIdAndOwnerId(13L, 7L)).thenReturn(Optional.of(domain(13L, CustomDomainStatus.DELETED, NOW)));

        assertThatThrownBy(() -> customDomainService.remove(12L, DomainLinkMode.DEACTIVATE))
                .isInstanceOf(CustomDomainNotFoundException.class)
                .hasMessage(NOT_FOUND);
        assertThatThrownBy(() -> customDomainService.remove(13L, DomainLinkMode.DEACTIVATE))
                .isInstanceOf(CustomDomainNotFoundException.class)
                .hasMessage(NOT_FOUND);
        verifyNoInteractions(customDomainRemoval);
    }

    @Test
    void theOwnerRetriesAFailedDomain() {
        final var failed = domain(12L, CustomDomainStatus.ERROR, NOW);
        failed.setProblem(DomainProblem.PROVISIONING_FAILED);
        final var retried = domain(12L, CustomDomainStatus.AWAITING_DNS, NOW);
        when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(failed), Optional.of(retried));
        when(rateLimiter.tryAcquire("domain-retry:domain:12", 1, Duration.ofSeconds(15))).thenReturn(true);

        final var response = customDomainService.retry(12L);

        assertThat(response.status()).isEqualTo(CustomDomainStatus.AWAITING_DNS);
        verify(customDomainSetup).retry(failed);
    }

    @Test
    void theOwnerCanRetryEveryFailedDomainAndASetupStillBeingRetried() {
        when(rateLimiter.tryAcquire("domain-retry:domain:12", 1, Duration.ofSeconds(15))).thenReturn(true);
        for (final var problem : List.of(DomainProblem.PROVISIONING_FAILED, DomainProblem.CNAME_REMOVED, DomainProblem.DNS_TIMED_OUT,
                DomainProblem.CLOUDFLARE_BLOCKED)) {
            final var failed = domain(12L, CustomDomainStatus.ERROR, NOW);
            failed.setProblem(problem);
            when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(failed));

            customDomainService.retry(12L);

            verify(customDomainSetup).retry(failed);
        }
        final var retrying = domain(12L, CustomDomainStatus.APPROVED_PROVISIONING, NOW);
        retrying.setProblem(DomainProblem.PROVISIONING_FAILED);
        when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(retrying));

        customDomainService.retry(12L);

        verify(customDomainSetup).retry(retrying);
    }

    @Test
    void theOwnerHasNothingToRetryOnAnyOtherStatus() {
        for (final var status : List.of(CustomDomainStatus.PENDING_REVIEW, CustomDomainStatus.APPROVED_PROVISIONING, CustomDomainStatus.AWAITING_DNS,
                CustomDomainStatus.ACTIVE, CustomDomainStatus.DISABLED, CustomDomainStatus.REJECTED)) {
            final var row = domain(12L, status, NOW);
            row.setProblem(status == CustomDomainStatus.AWAITING_DNS ? DomainProblem.DNS_NOT_FOUND : null);
            when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(row));

            assertThatThrownBy(() -> customDomainService.retry(12L))
                    .isInstanceOf(CustomDomainConflictException.class)
                    .hasMessage("There's nothing to retry for this domain.");
        }
        when(customDomainRepository.findByIdAndOwnerId(13L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> customDomainService.retry(13L)).isInstanceOf(CustomDomainNotFoundException.class).hasMessage(NOT_FOUND);
        verifyNoInteractions(customDomainSetup);
        verify(rateLimiter, never()).tryAcquire(eq("domain-retry:domain:12"), eq(1), any(Duration.class));
    }

    @Test
    void aSecondRetryWithinFifteenSecondsIsTooSoon() {
        final var failed = domain(12L, CustomDomainStatus.ERROR, NOW);
        failed.setProblem(DomainProblem.PROVISIONING_FAILED);
        when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(failed));
        when(rateLimiter.tryAcquire("domain-retry:domain:12", 1, Duration.ofSeconds(15))).thenReturn(true, false);

        customDomainService.retry(12L);

        assertThatThrownBy(() -> customDomainService.retry(12L))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("Wait a few seconds and try again.");
        verify(customDomainSetup, times(1)).retry(failed);
    }

    @Test
    void theCheckNowLimitNeverHoldsARetryBack() {
        final var failed = domain(12L, CustomDomainStatus.ERROR, NOW);
        failed.setProblem(DomainProblem.CNAME_REMOVED);
        when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(failed));
        when(rateLimiter.tryAcquire("domain-check:domain:12", 1, Duration.ofSeconds(15))).thenReturn(false);
        when(rateLimiter.tryAcquire("domain-retry:domain:12", 1, Duration.ofSeconds(15))).thenReturn(true);

        customDomainService.get(12L);
        customDomainService.retry(12L);

        verify(customDomainSetup).retry(failed);
        verify(rateLimiter, times(1)).tryAcquire("domain-check:domain:12", 1, Duration.ofSeconds(15));
    }

    @Test
    void aRetryGoesAheadWhenTheLimiterIsDown() {
        final var failed = domain(12L, CustomDomainStatus.ERROR, NOW);
        failed.setProblem(DomainProblem.PROVISIONING_FAILED);
        when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(failed));
        when(rateLimiter.tryAcquire("domain-retry:domain:12", 1, Duration.ofSeconds(15)))
                .thenThrow(new RateLimiterUnavailableException(new IllegalStateException("redis down")));

        customDomainService.retry(12L);

        verify(customDomainSetup).retry(failed);
    }

    @Test
    void aRetryTheCapOrCloudflareRefusesTellsTheOwnerPlainly() {
        final var failed = domain(12L, CustomDomainStatus.ERROR, NOW);
        failed.setProblem(DomainProblem.PROVISIONING_FAILED);
        when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(failed));
        when(rateLimiter.tryAcquire("domain-retry:domain:12", 1, Duration.ofSeconds(15))).thenReturn(true);
        doThrow(new CustomDomainCapacityException("Cap reached: 90 of 90 custom domains are in use. Delete one or raise the cap (APP_DOMAINS_MAX_ACTIVE)."))
                .when(customDomainSetup).retry(failed);

        assertThatThrownBy(() -> customDomainService.retry(12L))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("We can't set up your domain right now. Try again later.");
    }

    @Test
    void getShowsTheOwnersDomainWithItsLinks() {
        when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.of(domain(12L, CustomDomainStatus.PENDING_REVIEW, NOW)));
        when(urlRepository.countByDomain_Id(12L)).thenReturn(4L);

        final var response = customDomainService.get(12L);

        assertThat(response.id()).isEqualTo(12L);
        assertThat(response.links()).isEqualTo(4L);
    }

    @Test
    void getIsNotFoundForSomeoneElsesOrADeletedDomain() {
        when(customDomainRepository.findByIdAndOwnerId(12L, 7L)).thenReturn(Optional.empty());
        when(customDomainRepository.findByIdAndOwnerId(13L, 7L)).thenReturn(Optional.of(domain(13L, CustomDomainStatus.DELETED, NOW)));

        assertThatThrownBy(() -> customDomainService.get(12L)).isInstanceOf(CustomDomainNotFoundException.class).hasMessage(NOT_FOUND);
        assertThatThrownBy(() -> customDomainService.get(13L)).isInstanceOf(CustomDomainNotFoundException.class).hasMessage(NOT_FOUND);
    }

    @Test
    void mineListsNewestFirstAndHidesOldEndings() {
        final var pending = domain(20L, CustomDomainStatus.PENDING_REVIEW, NOW.minusHours(1));
        final var rejectedRecently = domain(19L, CustomDomainStatus.REJECTED, NOW.minusDays(40));
        rejectedRecently.setReviewedAt(NOW.minusDays(29));
        final var rejectedLongAgo = domain(18L, CustomDomainStatus.REJECTED, NOW.minusDays(40));
        rejectedLongAgo.setReviewedAt(NOW.minusDays(31));
        final var deletedRecently = domain(17L, CustomDomainStatus.DELETED, NOW.minusDays(50));
        deletedRecently.setDeletedAt(NOW.minusDays(2));
        final var deletedLongAgo = domain(16L, CustomDomainStatus.DELETED, NOW.minusDays(50));
        deletedLongAgo.setDeletedAt(NOW.minusDays(31));
        final var active = domain(15L, CustomDomainStatus.ACTIVE, NOW.minusDays(60));
        when(customDomainRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(7L))
                .thenReturn(List.of(pending, rejectedRecently, rejectedLongAgo, deletedRecently, deletedLongAgo, active));
        when(customDomainAllowanceRepository.findById(7L))
                .thenReturn(Optional.of(CustomDomainAllowance.builder().userId(7L).maxDomains(3).reason("Agency").build()));
        when(urlRepository.countByDomain_Id(anyLong())).thenAnswer(invocation -> invocation.<Long>getArgument(0) == 15L ? 9L : 0L);

        final var mine = customDomainService.mine();

        assertThat(mine.domains()).extracting(CustomDomainResponse::id).containsExactly(20L, 19L, 17L, 15L);
        assertThat(mine.domains()).extracting(CustomDomainResponse::links).containsExactly(0L, 0L, 0L, 9L);
        assertThat(mine.limit()).isEqualTo(3);
        assertThat(mine.used()).isEqualTo(2L);
        assertThat(mine.canApply()).isTrue();
    }

    @Test
    void mineCannotApplyAtTheLimitOrWhenTurnedOff() {
        when(customDomainRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(List.of());

        assertThat(customDomainService.mine().canApply()).isTrue();
        appProperties.getDomains().setEnabled(false);
        assertThat(customDomainService.mine().canApply()).isFalse();
        appProperties.getDomains().setEnabled(true);

        when(customDomainRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(7L))
                .thenReturn(List.of(domain(15L, CustomDomainStatus.AWAITING_DNS, NOW)));
        final var mine = customDomainService.mine();
        assertThat(mine.used()).isEqualTo(1L);
        assertThat(mine.limit()).isEqualTo(1);
        assertThat(mine.canApply()).isFalse();
    }

    private static CustomDomainApplyDto apply(final String hostname) {
        return CustomDomainApplyDto.builder().hostname(hostname).reason(REASON).build();
    }

    private static CustomDomain domain(final long id, final CustomDomainStatus status, final LocalDateTime createdAt) {
        return CustomDomain.builder().id(id).hostname("d" + id + ".example.com").hostnameKey("d" + id + ".example.com")
                .status(status).reason(REASON).createdAt(createdAt).updatedAt(createdAt).build();
    }

    private static DataIntegrityViolationException violation(final String constraint) {
        return new DataIntegrityViolationException("could not execute statement", new ConstraintViolationException(
                "could not execute statement", new SQLException("duplicate key value violates unique constraint \"" + constraint + "\"", "23505"),
                constraint));
    }

}
