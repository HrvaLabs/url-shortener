package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.domain.CloudflareCheckResponse;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceDto;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceResponse;
import cc.hrva.urlshortener.dto.domain.DomainOwnerSummary;
import cc.hrva.urlshortener.dto.domain.DomainReviewDto;
import cc.hrva.urlshortener.dto.domain.DomainSummaryResponse;
import cc.hrva.urlshortener.dto.domain.ReconcileResponse;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.CustomDomainAllowance;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainCapLock;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.MissingDomain;
import cc.hrva.urlshortener.repository.CustomDomainReconcileRepository.ReconcileRun;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.DomainLinkCount;
import cc.hrva.urlshortener.repository.OwnerLinkCount;
import cc.hrva.urlshortener.repository.OwnerReviewCount;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.domain.AdminDomainResponses;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck.TokenState;
import cc.hrva.urlshortener.service.domain.CustomDomainChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainProvisioner;
import cc.hrva.urlshortener.service.domain.CustomDomainResponses;
import cc.hrva.urlshortener.service.impl.DefaultCustomDomainAdminService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCustomDomainAdminServiceTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-10-29T09:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    private static final String ADMIN = "admin@hrva.cc";
    private static final String ALREADY_REVIEWED = "This application was already reviewed.";
    private static final String DEACTIVATED = "This account is deactivated, so its domain can't be approved.";
    private static final String NOT_FOUND = "Custom domain not found";
    private static final long ID = 12L;
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATORS.getValidator();

    @Mock
    private CustomDomainRepository customDomainRepository;

    @Mock
    private CustomDomainAllowanceRepository customDomainAllowanceRepository;

    @Mock
    private CustomDomainReconcileRepository customDomainReconcileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CustomDomainCapLock customDomainCapLock;

    @Mock
    private CloudflareTokenCheck cloudflareTokenCheck;

    @Mock
    private CustomDomainProvisioner customDomainProvisioner;

    @Mock
    private ScammerSignalService scammerSignalService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CloudflareClient cloudflareClient;

    private final User ana = User.builder().id(7L).email("ana@example.com").active(true).createDate(NOW.minusDays(40).plusNanos(5000)).build();
    private final List<String> steps = new ArrayList<>();
    private AppProperties appProperties;
    private CustomDomainAdminService customDomainAdminService;

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("https://app.hrva.cc");
        final var clock = Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
        lenient().when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.OK, 4));
        lenient().when(customDomainRepository.findById(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.PENDING_REVIEW)));
        lenient().when(customDomainRepository.findWithOwner(ID))
                .thenReturn(Optional.of(domain(CustomDomainStatus.PENDING_REVIEW)), Optional.of(approved()));
        lenient().when(customDomainRepository.markApproved(eq(ID), eq(ADMIN), any(), eq(NOW))).thenAnswer(invocation -> {
            steps.add("markApproved");
            return 1;
        });
        lenient().when(customDomainRepository.countSlotsInUse()).thenReturn(3L);
        lenient().doAnswer(invocation -> steps.add("provision")).when(customDomainProvisioner).provisionAsync(anyLong());
        final var responses = new AdminDomainResponses(customDomainRepository, new CustomDomainResponses(appProperties), scammerSignalService, clock);
        customDomainAdminService = new DefaultCustomDomainAdminService(customDomainRepository, customDomainAllowanceRepository,
                customDomainReconcileRepository, userRepository, customDomainCapLock, cloudflareTokenCheck, customDomainProvisioner, responses,
                eventPublisher, recordingTransactions(), appProperties, clock);
    }

    @Test
    void approveMovesToProvisioningAndHandsOver() {
        final var response = customDomainAdminService.approve(ID, "  Looks fine  ", ADMIN);

        assertThat(response.domain().status()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
        assertThat(response.domain().id()).isEqualTo(ID);
        verify(customDomainCapLock).lock();
        verify(customDomainRepository).markApproved(ID, ADMIN, "Looks fine", NOW);
        verify(eventPublisher).publishEvent(new CustomDomainChangedEvent(ID));
        verify(customDomainProvisioner).provisionAsync(ID);
        assertThat(steps).containsExactly("begin", "markApproved", "commit", "provision");
    }

    @Test
    void anApprovalWithoutANoteStoresNone() {
        customDomainAdminService.approve(ID, "   ", ADMIN);

        verify(customDomainRepository).markApproved(eq(ID), eq(ADMIN), isNull(), eq(NOW));
    }

    @Test
    void approvalNeedsAnOkToken() {
        final var texts = Map.of(
                CloudflareTokenStatus.MISSING, "the API token is missing",
                CloudflareTokenStatus.INVALID, "the API token is invalid",
                CloudflareTokenStatus.NO_PERMISSION, "the API token can't manage custom hostnames",
                CloudflareTokenStatus.UNREACHABLE, "Cloudflare can't be reached");
        texts.forEach((status, text) -> {
            when(cloudflareTokenCheck.check()).thenReturn(token(status, null));

            assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                    .isInstanceOf(CustomDomainConflictException.class)
                    .hasMessage("Cloudflare isn't set up: " + text + ". Check CLOUDFLARE_API_TOKEN and CLOUDFLARE_ZONE_ID.");
        });

        assertThat(texts).hasSize(CloudflareTokenStatus.values().length - 1);
        verifyNoInteractions(customDomainCapLock, customDomainProvisioner, eventPublisher);
        verify(customDomainRepository, never()).markApproved(anyLong(), anyString(), any(), any());
        assertThat(steps).isEmpty();
    }

    @Test
    void approvalNeedsALiveTotalBelowTheFreeAllowance() {
        when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.OK, 100));

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("Cloudflare already has 100 custom hostnames and 100 are free. Delete one here or in Cloudflare first.");
        verify(customDomainRepository, never()).markApproved(anyLong(), anyString(), any(), any());
        verifyNoInteractions(customDomainProvisioner);

        when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.OK, 99));
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.PENDING_REVIEW)), Optional.of(approved()));

        assertThat(customDomainAdminService.approve(ID, null, ADMIN).domain().status()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
        verify(customDomainProvisioner).provisionAsync(ID);
    }

    @Test
    void anOkTokenWithoutALiveTotalCannotProveTheAllowance() {
        when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.OK, null));

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("Cloudflare isn't set up: Cloudflare can't be reached. Check CLOUDFLARE_API_TOKEN and CLOUDFLARE_ZONE_ID.");
        verifyNoInteractions(customDomainCapLock, customDomainProvisioner);
    }

    @Test
    void theFreeAllowanceIsReadFromTheSettings() {
        appProperties.getDomains().setFreeAllowance(5);
        when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.OK, 5));

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .hasMessage("Cloudflare already has 5 custom hostnames and 5 are free. Delete one here or in Cloudflare first.");
    }

    @Test
    void approvalRespectsTheCap() {
        when(customDomainRepository.countSlotsInUse()).thenReturn(90L);

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("Cap reached: 90 of 90 custom domains are in use. Delete one or raise the cap (APP_DOMAINS_MAX_ACTIVE).");
        verify(customDomainRepository, never()).markApproved(anyLong(), anyString(), any(), any());
        verifyNoInteractions(customDomainProvisioner);

        when(customDomainRepository.countSlotsInUse()).thenReturn(89L);
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.PENDING_REVIEW)), Optional.of(approved()));

        assertThat(customDomainAdminService.approve(ID, null, ADMIN).domain().status()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
    }

    @Test
    void theCapIsCheckedUnderTheLock() {
        when(customDomainRepository.countSlotsInUse()).thenAnswer(invocation -> {
            verify(customDomainCapLock).lock();
            steps.add("count");
            return 3L;
        });

        customDomainAdminService.approve(ID, null, ADMIN);

        assertThat(steps).containsExactly("begin", "count", "markApproved", "commit", "provision");
    }

    @Test
    void theCapIsReadFromTheSettings() {
        appProperties.getDomains().setMaxActive(3);

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .hasMessage("Cap reached: 3 of 3 custom domains are in use. Delete one or raise the cap (APP_DOMAINS_MAX_ACTIVE).");
    }

    @Test
    void approvalIsOnceOnly() {
        for (final var status : List.of(CustomDomainStatus.ACTIVE, CustomDomainStatus.REJECTED, CustomDomainStatus.APPROVED_PROVISIONING,
                CustomDomainStatus.DELETED)) {
            when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(domain(status)));

            assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                    .isInstanceOf(CustomDomainConflictException.class)
                    .hasMessage(ALREADY_REVIEWED);
        }

        verify(customDomainRepository, never()).markApproved(anyLong(), anyString(), any(), any());
        verifyNoInteractions(customDomainProvisioner, eventPublisher);
    }

    @Test
    void anApprovalThatLosesTheRowToAnotherReviewIsAlreadyReviewed() {
        when(customDomainRepository.markApproved(eq(ID), eq(ADMIN), any(), eq(NOW))).thenReturn(0);

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage(ALREADY_REVIEWED);
        verifyNoInteractions(customDomainProvisioner, eventPublisher);
    }

    @Test
    void aDeactivatedOwnersApplicationCannotBeApproved() {
        final var deactivated = domain(CustomDomainStatus.PENDING_REVIEW);
        deactivated.setOwner(User.builder().id(7L).email("ana@example.com").active(false).build());
        final var ownerless = domain(CustomDomainStatus.PENDING_REVIEW);
        ownerless.setOwner(null);

        for (final var domain : List.of(deactivated, ownerless)) {
            when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(domain));

            assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                    .isInstanceOf(CustomDomainConflictException.class)
                    .hasMessage(DEACTIVATED);
        }

        verify(customDomainRepository, never()).markApproved(anyLong(), anyString(), any(), any());
        verifyNoInteractions(customDomainProvisioner);
    }

    @Test
    void approvingAnUnknownDomainIsNotFound() {
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .isInstanceOf(CustomDomainNotFoundException.class)
                .hasMessage(NOT_FOUND);
        verifyNoInteractions(customDomainProvisioner);
    }

    @Test
    void aMissingApplicationIsNotFoundWithoutAskingCloudflare() {
        when(customDomainRepository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .isInstanceOf(CustomDomainNotFoundException.class)
                .hasMessage(NOT_FOUND);
        verifyNoInteractions(cloudflareTokenCheck, customDomainCapLock, customDomainProvisioner);
        assertThat(steps).isEmpty();
    }

    @Test
    void anAlreadyReviewedApplicationIsRefusedWithoutAskingCloudflare() {
        lenient().when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.UNREACHABLE, null));
        for (final var status : List.of(CustomDomainStatus.ACTIVE, CustomDomainStatus.REJECTED, CustomDomainStatus.APPROVED_PROVISIONING)) {
            when(customDomainRepository.findById(ID)).thenReturn(Optional.of(domain(status)));

            assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                    .isInstanceOf(CustomDomainConflictException.class)
                    .hasMessage(ALREADY_REVIEWED);
        }

        verify(cloudflareTokenCheck, never()).check();
        verifyNoInteractions(customDomainCapLock, customDomainProvisioner, eventPublisher);
        assertThat(steps).isEmpty();
    }

    @Test
    void thePendingCheckComesBeforeTheTokenCheck() {
        customDomainAdminService.approve(ID, null, ADMIN);

        final var order = inOrder(customDomainRepository, cloudflareTokenCheck, customDomainCapLock);
        order.verify(customDomainRepository).findById(ID);
        order.verify(cloudflareTokenCheck).check();
        order.verify(customDomainCapLock).lock();
        order.verify(customDomainRepository).findWithOwner(ID);
    }

    @Test
    void applicationsApprovedButNotYetInCloudflareCountTowardTheFreeAllowance() {
        when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.OK, 99));
        doAnswer(invocation -> steps.add("lock")).when(customDomainCapLock).lock();
        when(customDomainRepository.countByStatus(CustomDomainStatus.APPROVED_PROVISIONING)).thenAnswer(invocation -> {
            steps.add("notYetInCloudflare");
            return 1L;
        });

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("Cloudflare already has 100 custom hostnames and 100 are free. Delete one here or in Cloudflare first.");
        assertThat(steps).containsExactly("begin", "lock", "notYetInCloudflare");
        verify(customDomainRepository, never()).markApproved(anyLong(), anyString(), any(), any());
        verifyNoInteractions(customDomainProvisioner);

        when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.OK, 98));
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.PENDING_REVIEW)), Optional.of(approved()));

        assertThat(customDomainAdminService.approve(ID, null, ADMIN).domain().status()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
    }

    @Test
    void aHandOverThatCannotStartKeepsTheApproval() {
        doThrow(new IllegalStateException("queue full")).when(customDomainProvisioner).provisionAsync(ID);

        final var response = customDomainAdminService.approve(ID, null, ADMIN);

        assertThat(response.domain().status()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
        verify(customDomainRepository).markApproved(ID, ADMIN, null, NOW);
    }

    @Test
    void rejectFreesTheHostname() {
        when(customDomainRepository.findById(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.PENDING_REVIEW)));
        when(customDomainRepository.markRejected(ID, ADMIN, "Not your domain", NOW)).thenReturn(1);
        final var rejected = domain(CustomDomainStatus.REJECTED);
        rejected.setHostnameKey(null);
        rejected.setReviewNote("Not your domain");
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(rejected));

        final var response = customDomainAdminService.reject(ID, " Not your domain ", ADMIN);

        assertThat(response.domain().status()).isEqualTo(CustomDomainStatus.REJECTED);
        assertThat(response.domain().reviewNote()).isEqualTo("Not your domain");
        verify(customDomainRepository).markRejected(ID, ADMIN, "Not your domain", NOW);
        verify(eventPublisher).publishEvent(new CustomDomainChangedEvent(ID));
    }

    @Test
    void rejectNeverCallsCloudflare() {
        when(customDomainRepository.findById(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.PENDING_REVIEW)));
        when(customDomainRepository.markRejected(ID, ADMIN, null, NOW)).thenReturn(1);
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.REJECTED)));

        customDomainAdminService.reject(ID, null, ADMIN);

        verifyNoInteractions(cloudflareTokenCheck, customDomainProvisioner, customDomainCapLock, cloudflareClient);
    }

    @Test
    void rejectIsOnceOnly() {
        when(customDomainRepository.findById(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.ACTIVE)));

        assertThatThrownBy(() -> customDomainAdminService.reject(ID, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage(ALREADY_REVIEWED);

        when(customDomainRepository.findById(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.PENDING_REVIEW)));
        when(customDomainRepository.markRejected(ID, ADMIN, null, NOW)).thenReturn(0);

        assertThatThrownBy(() -> customDomainAdminService.reject(ID, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage(ALREADY_REVIEWED);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rejectingAnUnknownDomainIsNotFound() {
        when(customDomainRepository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customDomainAdminService.reject(ID, null, ADMIN))
                .isInstanceOf(CustomDomainNotFoundException.class)
                .hasMessage(NOT_FOUND);
    }

    @Test
    void onlyTheProvisionerCreatesHostnames() {
        final var provisioner = new CustomDomainProvisioner(customDomainRepository, cloudflareClient);
        final var notApproved = Arrays.stream(CustomDomainStatus.values()).filter(status -> status != CustomDomainStatus.APPROVED_PROVISIONING).toList();
        for (final var status : notApproved) {
            final var row = domain(status);
            row.setReviewedAt(NOW);
            when(customDomainRepository.findById(ID)).thenReturn(Optional.of(row));

            provisioner.provision(ID);
        }
        final var unreviewed = domain(CustomDomainStatus.APPROVED_PROVISIONING);
        when(customDomainRepository.findById(ID)).thenReturn(Optional.of(unreviewed));
        provisioner.provision(ID);
        when(customDomainRepository.findById(ID)).thenReturn(Optional.empty());
        provisioner.provision(ID);

        verifyNoInteractions(cloudflareClient);
        verify(customDomainRepository, times(notApproved.size() + 2)).findById(ID);
    }

    @Test
    void theAsyncHandOverRunsTheProvisioner() {
        final var provisioner = new CustomDomainProvisioner(customDomainRepository, cloudflareClient);
        when(customDomainRepository.findById(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.REJECTED)));

        provisioner.provisionAsync(ID);

        verify(customDomainRepository).findById(ID);
        verifyNoInteractions(cloudflareClient);
    }

    @Test
    void theListLoadsOwnersCountsAndClicksOncePerPage() {
        final var bob = User.builder().id(8L).email("bob@example.com").active(false).createDate(NOW.minusDays(2)).build();
        final var first = domain(CustomDomainStatus.ACTIVE);
        first.setApplyFlags(List.of("Registered 3 days ago"));
        first.setCloudflareId("b0361ed9-0000-4000-8000-000000000001");
        first.setCloudflareStatus("active");
        first.setCloudflareSslStatus("active");
        final var second = domain(CustomDomainStatus.PENDING_REVIEW);
        second.setId(13L);
        second.setHostname("links.example.org");
        second.setOwner(bob);
        final var third = domain(CustomDomainStatus.DELETED);
        third.setId(14L);
        third.setOwner(null);
        third.setDeletedAt(NOW.minusDays(1).plusNanos(700));
        when(customDomainRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(first, second, third), invocation.getArgument(1), 3));
        when(customDomainRepository.countLinksOfOwners(anyCollection())).thenReturn(List.of(new OwnerLinkCount(7L, 42L, 2L)));
        when(customDomainRepository.countOpenReviewsOfOwners(anyCollection())).thenReturn(List.of(new OwnerReviewCount(8L, 1L)));
        when(customDomainRepository.countLinksOfDomains(anyCollection())).thenReturn(List.of(new DomainLinkCount(ID, 5L, 1204L)));
        when(scammerSignalService.possibleScammers(anyCollection())).thenReturn(Set.of(8L));

        final var page = customDomainAdminService.list(CustomDomainStatus.ACTIVE, " go ", PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);
        final var rows = page.getContent();
        assertThat(rows).extracting(row -> row.domain().id()).containsExactly(ID, 13L, 14L);
        assertThat(rows.getFirst().owner())
                .isEqualTo(new DomainOwnerSummary(7L, "ana@example.com", true, NOW.minusDays(40), 42L, 2L, 0L, false));
        assertThat(rows.getFirst().domain().links()).isEqualTo(5L);
        assertThat(rows.getFirst().clicks()).isEqualTo(1204L);
        assertThat(rows.getFirst().applyFlags()).containsExactly("Registered 3 days ago");
        assertThat(rows.getFirst().cloudflareId()).isEqualTo("b0361ed9-0000-4000-8000-000000000001");
        assertThat(rows.getFirst().sslStatus()).isEqualTo("active");
        assertThat(rows.get(1).owner()).isEqualTo(new DomainOwnerSummary(8L, "bob@example.com", false, NOW.minusDays(2), 0L, 0L, 1L, true));
        assertThat(rows.get(1).domain().links()).isZero();
        assertThat(rows.get(1).clicks()).isZero();
        assertThat(rows.get(1).applyFlags()).isEmpty();
        assertThat(rows.get(2).owner()).isNull();
        assertThat(rows.get(2).deletedAt()).isEqualTo(NOW.minusDays(1));
        verify(customDomainRepository).countLinksOfOwners(argThat(ids(7L, 8L)));
        verify(customDomainRepository).countOpenReviewsOfOwners(argThat(ids(7L, 8L)));
        verify(customDomainRepository).countLinksOfDomains(argThat(ids(ID, 13L, 14L)));
        verify(scammerSignalService, times(1)).possibleScammers(anyCollection());
    }

    @Test
    void anEmptyPageRunsNoCountQueries() {
        when(customDomainRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        assertThat(customDomainAdminService.list(null, null, PageRequest.of(0, 20)).getContent()).isEmpty();

        verify(customDomainRepository, never()).countLinksOfOwners(anyCollection());
        verify(customDomainRepository, never()).countOpenReviewsOfOwners(anyCollection());
        verify(customDomainRepository, never()).countLinksOfDomains(anyCollection());
        verifyNoInteractions(scammerSignalService);
    }

    @Test
    void ownerlessPagesAskNothingAboutOwners() {
        final var ownerless = domain(CustomDomainStatus.DELETED);
        ownerless.setOwner(null);
        when(customDomainRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(ownerless), invocation.getArgument(1), 1));

        assertThat(customDomainAdminService.list(null, null, PageRequest.of(0, 20)).getContent()).singleElement()
                .satisfies(row -> assertThat(row.owner()).isNull());

        verify(customDomainRepository, never()).countLinksOfOwners(anyCollection());
        verify(customDomainRepository, never()).countOpenReviewsOfOwners(anyCollection());
        verify(customDomainRepository).countLinksOfDomains(argThat(ids(ID)));
        verifyNoInteractions(scammerSignalService);
    }

    @Test
    void largeIdListsAreAskedForInChunksOfAThousand() {
        final var owners = LongStream.rangeClosed(1, 2500)
                .mapToObj(id -> User.builder().id(id).email("u" + id + "@example.com").active(true).createDate(NOW).build())
                .toList();
        final var domains = owners.stream().map(owner -> {
            final var domain = domain(CustomDomainStatus.ACTIVE);
            domain.setId(owner.getId() + 10_000);
            domain.setOwner(owner);
            return domain;
        }).toList();
        when(customDomainRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(domains, invocation.getArgument(1), domains.size()));
        final var asked = new ArrayList<Integer>();
        when(customDomainRepository.countLinksOfDomains(anyCollection())).thenAnswer(invocation -> {
            final Collection<Long> ids = invocation.getArgument(0);
            asked.add(ids.size());
            return List.of();
        });

        customDomainAdminService.list(null, null, PageRequest.of(0, 2500));

        assertThat(asked).containsExactly(1000, 1000, 500);
        verify(customDomainRepository, times(3)).countLinksOfOwners(anyCollection());
        verify(customDomainRepository, times(3)).countOpenReviewsOfOwners(anyCollection());
    }

    @Test
    void summaryShowsSlotsTotalsAndTheToken() {
        appProperties.getDomains().setIgnoredHostnames(List.of("test.karlo.cc"));
        when(customDomainRepository.countSlotsInUse()).thenReturn(3L);
        when(customDomainRepository.countByStatus(CustomDomainStatus.PENDING_REVIEW)).thenReturn(1L);
        when(customDomainRepository.countByStatus(CustomDomainStatus.ACTIVE)).thenReturn(2L);
        when(cloudflareTokenCheck.current()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW.minusMinutes(5), null, 4));
        when(customDomainReconcileRepository.newest()).thenReturn(Optional.of(new ReconcileRun(NOW.minusHours(6).plusNanos(900), 3, 3, List.of(),
                List.of(new MissingDomain(9L, "x.example.org")), null)));

        final var summary = customDomainAdminService.summary();

        assertThat(summary).isEqualTo(new DomainSummaryResponse(3L, 90, 100, 4, null, 1L, 2L,
                new CloudflareCheckResponse(CloudflareTokenStatus.OK, NOW.minusMinutes(5), null),
                new ReconcileResponse(NOW.minusHours(6), 3, 3, List.of(), List.of(new ReconcileResponse.MissingDomain(9L, "x.example.org")),
                        List.of("test.karlo.cc"), null)));
        verify(cloudflareTokenCheck, never()).check();
    }

    @Test
    void theSummaryTotalIsTheNewestReading() {
        when(cloudflareTokenCheck.current()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW.minusHours(2), null, 4));
        when(customDomainReconcileRepository.newest()).thenReturn(Optional.of(new ReconcileRun(NOW.minusHours(1), 6, 5, List.of(), List.of(), null)));

        assertThat(customDomainAdminService.summary().cloudflareTotal()).isEqualTo(6);

        when(cloudflareTokenCheck.current()).thenReturn(new TokenState(CloudflareTokenStatus.UNREACHABLE, NOW, "Cloudflare couldn't be reached. We'll check again.", null));

        assertThat(customDomainAdminService.summary().cloudflareTotal()).isEqualTo(6);
    }

    @Test
    void beforeAnyReadingTheSummaryHasNoTotalAndTheTokenIsNotCheckedYet() {
        when(cloudflareTokenCheck.current()).thenReturn(new TokenState(CloudflareTokenStatus.MISSING, null, "CLOUDFLARE_API_TOKEN or CLOUDFLARE_ZONE_ID isn't set.", null));
        when(customDomainReconcileRepository.newest()).thenReturn(Optional.empty());

        final var summary = customDomainAdminService.summary();

        assertThat(summary.cloudflareTotal()).isNull();
        assertThat(summary.cloudflareQuota()).isNull();
        assertThat(summary.reconcile()).isNull();
        assertThat(summary.token()).isEqualTo(new CloudflareCheckResponse(CloudflareTokenStatus.MISSING, null,
                "CLOUDFLARE_API_TOKEN or CLOUDFLARE_ZONE_ID isn't set."));
    }

    @Test
    void checkCloudflareRunsTheTokenCheck() {
        when(cloudflareTokenCheck.check()).thenReturn(new TokenState(CloudflareTokenStatus.NO_PERMISSION, NOW,
                "The token works but can't read custom hostnames on this zone.", null));

        assertThat(customDomainAdminService.checkCloudflare()).isEqualTo(new CloudflareCheckResponse(CloudflareTokenStatus.NO_PERMISSION, NOW,
                "The token works but can't read custom hostnames on this zone."));
    }

    @Test
    void allowanceIsOneToTwentyWithAReason() {
        assertThat(violations(new DomainAllowanceDto(0, "Agency"))).isEqualTo(Map.of("maxDomains", "Choose 1 to 20"));
        assertThat(violations(new DomainAllowanceDto(21, "Agency"))).isEqualTo(Map.of("maxDomains", "Choose 1 to 20"));
        assertThat(violations(new DomainAllowanceDto(null, "Agency"))).isEqualTo(Map.of("maxDomains", "Choose 1 to 20"));
        assertThat(violations(new DomainAllowanceDto(3, "   "))).isEqualTo(Map.of("reason", "Reason is required"));
        assertThat(violations(new DomainAllowanceDto(3, null))).isEqualTo(Map.of("reason", "Reason is required"));
        assertThat(violations(new DomainAllowanceDto(3, "a".repeat(301)))).isEqualTo(Map.of("reason", "Keep it to 300 characters or fewer"));
        assertThat(violations(new DomainAllowanceDto(1, " " + "a".repeat(300) + " "))).isEmpty();
        assertThat(violations(new DomainAllowanceDto(20, "Agency with three brands"))).isEmpty();
    }

    @Test
    void aReviewNoteIsUpTo500CharactersAfterTrimming() {
        assertThat(VALIDATOR.validate(new DomainReviewDto("a".repeat(501)))).extracting(violation -> violation.getMessage())
                .containsExactly("Keep it to 500 characters or fewer");
        assertThat(VALIDATOR.validate(new DomainReviewDto("  " + "a".repeat(500) + "  "))).isEmpty();
        assertThat(new DomainReviewDto("   ").getNote()).isNull();
        assertThat(new DomainReviewDto(" Looks fine ").getNote()).isEqualTo("Looks fine");
    }

    @Test
    void theDefaultAllowanceIsThePerUserLimit() {
        when(userRepository.existsById(7L)).thenReturn(true);
        when(customDomainAllowanceRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(customDomainAdminService.allowance(7L)).isEqualTo(new DomainAllowanceResponse(7L, 1, null, null, null));
    }

    @Test
    void aGrantedAllowanceIsShown() {
        when(userRepository.existsById(7L)).thenReturn(true);
        when(customDomainAllowanceRepository.findById(7L)).thenReturn(Optional.of(CustomDomainAllowance.builder().userId(7L).maxDomains(3)
                .reason("Agency with three brands").grantedBy(ADMIN).grantedAt(NOW.minusDays(1).plusNanos(1234)).build()));

        assertThat(customDomainAdminService.allowance(7L))
                .isEqualTo(new DomainAllowanceResponse(7L, 3, "Agency with three brands", ADMIN, NOW.minusDays(1)));
    }

    @Test
    void anUnknownAccountHasNoAllowance() {
        when(userRepository.existsById(99L)).thenReturn(false);
        when(userRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customDomainAdminService.allowance(99L)).isInstanceOf(UserNotFoundException.class).hasMessage("User not found");
        assertThatThrownBy(() -> customDomainAdminService.setAllowance(99L, new DomainAllowanceDto(3, "Agency"), ADMIN))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> customDomainAdminService.removeAllowance(99L)).isInstanceOf(UserNotFoundException.class);
        verify(customDomainAllowanceRepository, never()).saveAndFlush(any());
        verify(customDomainAllowanceRepository, never()).delete(any());
    }

    @Test
    void settingAnAllowanceGrantsIt() {
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(ana));
        when(customDomainAllowanceRepository.findById(7L)).thenReturn(Optional.empty());
        when(customDomainAllowanceRepository.saveAndFlush(any(CustomDomainAllowance.class))).thenAnswer(invocation -> invocation.getArgument(0));

        final var response = customDomainAdminService.setAllowance(7L, new DomainAllowanceDto(3, " Agency with three brands "), ADMIN);

        assertThat(response).isEqualTo(new DomainAllowanceResponse(7L, 3, "Agency with three brands", ADMIN, NOW));
        verify(customDomainAllowanceRepository).saveAndFlush(argThat(allowance -> allowance.getUserId() == 7L
                && allowance.getMaxDomains() == 3 && "Agency with three brands".equals(allowance.getReason())
                && ADMIN.equals(allowance.getGrantedBy()) && NOW.equals(allowance.getGrantedAt())));
    }

    @Test
    void settingAnAllowanceAgainReplacesIt() {
        final var existing = CustomDomainAllowance.builder().userId(7L).maxDomains(3).reason("Agency").grantedBy("other@hrva.cc")
                .grantedAt(NOW.minusDays(9)).build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(ana));
        when(customDomainAllowanceRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(customDomainAllowanceRepository.saveAndFlush(existing)).thenReturn(existing);

        final var response = customDomainAdminService.setAllowance(7L, new DomainAllowanceDto(5, "Two more brands"), ADMIN);

        assertThat(response).isEqualTo(new DomainAllowanceResponse(7L, 5, "Two more brands", ADMIN, NOW));
        assertThat(existing.getMaxDomains()).isEqualTo(5);
    }

    @Test
    void removingAnAllowanceGoesBackToTheDefault() {
        final var existing = CustomDomainAllowance.builder().userId(7L).maxDomains(3).reason("Agency").grantedBy(ADMIN).grantedAt(NOW).build();
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(ana));
        when(customDomainAllowanceRepository.findById(7L)).thenReturn(Optional.of(existing), Optional.empty());

        customDomainAdminService.removeAllowance(7L);
        customDomainAdminService.removeAllowance(7L);

        verify(customDomainAllowanceRepository, times(1)).delete(existing);
    }

    private Map<String, String> violations(final DomainAllowanceDto dto) {
        return VALIDATOR.validate(dto).stream()
                .collect(Collectors.toMap(violation -> violation.getPropertyPath().toString(), violation -> violation.getMessage()));
    }

    private TransactionOperations recordingTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(final TransactionCallback<T> action) {
                steps.add("begin");
                final TransactionStatus status = new SimpleTransactionStatus();
                final var result = action.doInTransaction(status);
                steps.add("commit");
                return result;
            }
        };
    }

    private CustomDomain domain(final CustomDomainStatus status) {
        return CustomDomain.builder()
                .id(ID)
                .owner(ana)
                .hostname("go.example.com")
                .hostnameKey("go.example.com")
                .status(status)
                .reason("Menu links for my cafe")
                .createdAt(NOW.minusDays(1))
                .updatedAt(NOW.minusDays(1))
                .build();
    }

    private CustomDomain approved() {
        final var approved = domain(CustomDomainStatus.APPROVED_PROVISIONING);
        approved.setReviewedAt(NOW);
        approved.setReviewedBy(ADMIN);
        approved.setNextCheckAt(NOW);
        return approved;
    }

    private static TokenState token(final CloudflareTokenStatus status, final Integer total) {
        return new TokenState(status, NOW, null, total);
    }

    private static ArgumentMatcher<Collection<Long>> ids(final Long... expected) {
        return actual -> actual != null && actual.size() == expected.length && Set.copyOf(actual).equals(Set.of(expected));
    }

}
