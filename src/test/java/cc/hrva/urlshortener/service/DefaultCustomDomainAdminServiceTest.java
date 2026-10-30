package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.domain.CloudflareCheckResponse;
import cc.hrva.urlshortener.dto.domain.DnsRecord;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceDto;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceResponse;
import cc.hrva.urlshortener.dto.domain.DomainDisableDto;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.DomainOwnerSummary;
import cc.hrva.urlshortener.dto.domain.DomainReviewDto;
import cc.hrva.urlshortener.dto.domain.DomainSummaryResponse;
import cc.hrva.urlshortener.dto.domain.ReconcileResponse;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.CustomDomainAllowance;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import cc.hrva.urlshortener.model.enums.DomainProblem;
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
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainDisabled;
import cc.hrva.urlshortener.service.domain.AdminDomainResponses;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck;
import cc.hrva.urlshortener.service.domain.CloudflareTokenCheck.TokenState;
import cc.hrva.urlshortener.service.domain.CustomDomainChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainLifecycle;
import cc.hrva.urlshortener.service.domain.CustomDomainModeration;
import cc.hrva.urlshortener.service.domain.CustomDomainProvisioner;
import cc.hrva.urlshortener.service.domain.CustomDomainReconciler;
import cc.hrva.urlshortener.service.domain.CustomDomainReconciler.QuotaReading;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval;
import cc.hrva.urlshortener.service.domain.CustomDomainRemoval.Actor;
import cc.hrva.urlshortener.service.domain.CustomDomainResponses;
import cc.hrva.urlshortener.service.domain.CustomDomainSetup;
import cc.hrva.urlshortener.service.domain.LifecycleOutcome;
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
import org.springframework.core.task.TaskRejectedException;
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
    private CustomDomainLifecycle customDomainLifecycle;

    @Mock
    private CustomDomainReconciler customDomainReconciler;

    @Mock
    private ScammerSignalService scammerSignalService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CloudflareClient cloudflareClient;

    @Mock
    private CustomDomainRemoval customDomainRemoval;

    @Mock
    private LinkCheckService linkCheckService;

    @Mock
    private SendingEmailService sendingEmailService;

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
        final var transactions = recordingTransactions();
        final var setup = new CustomDomainSetup(customDomainRepository, customDomainCapLock, cloudflareTokenCheck, customDomainProvisioner,
                customDomainLifecycle, eventPublisher, transactions, appProperties, clock);
        final var moderation = new CustomDomainModeration(customDomainRepository, customDomainLifecycle, linkCheckService, sendingEmailService,
                eventPublisher, transactions, clock);
        customDomainAdminService = new DefaultCustomDomainAdminService(customDomainRepository, customDomainAllowanceRepository,
                customDomainReconcileRepository, userRepository, customDomainCapLock, cloudflareTokenCheck, customDomainProvisioner, setup, moderation,
                customDomainRemoval, customDomainReconciler, responses, linkCheckService, sendingEmailService, eventPublisher, transactions, appProperties,
                clock);
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
                .hasMessage("Cap reached: 90 of 90 custom domains are in use. Remove one or raise the cap (APP_DOMAINS_MAX_ACTIVE).");
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
                .hasMessage("Cap reached: 3 of 3 custom domains are in use. Remove one or raise the cap (APP_DOMAINS_MAX_ACTIVE).");
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
    void approvalRefusesADomainOnABlockRuleWithoutAskingCloudflare() {
        when(linkCheckService.isBlockedByRule("https://go.example.com/")).thenReturn(true);

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("This domain matches a block rule.");

        verifyNoInteractions(cloudflareTokenCheck, customDomainCapLock, customDomainProvisioner);
        verify(customDomainRepository, never()).markApproved(anyLong(), any(), any(), any());
        verify(linkCheckService, never()).check(anyString());
    }

    @Test
    void aBlockRuleAddedWhileCloudflareWasAskedStillRefusesTheApproval() {
        when(linkCheckService.isBlockedByRule("https://go.example.com/")).thenReturn(false, true);

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN))
                .isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("This domain matches a block rule.");

        final var order = inOrder(cloudflareTokenCheck, customDomainCapLock, linkCheckService);
        order.verify(linkCheckService).isBlockedByRule("https://go.example.com/");
        order.verify(cloudflareTokenCheck).check();
        order.verify(customDomainCapLock).lock();
        order.verify(linkCheckService).isBlockedByRule("https://go.example.com/");
        verify(customDomainRepository, never()).markApproved(anyLong(), any(), any(), any());
        verifyNoInteractions(customDomainProvisioner);
    }

    @Test
    void aHandOverThatCannotStartKeepsTheApproval() {
        doThrow(new IllegalStateException("queue full")).when(customDomainProvisioner).provisionAsync(ID);

        final var response = customDomainAdminService.approve(ID, null, ADMIN);

        assertThat(response.domain().status()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
        verify(customDomainRepository).markApproved(ID, ADMIN, null, NOW);
    }

    @Test
    void approveEmailsTheOwnerAfterCommit() {
        doAnswer(invocation -> steps.add("email")).when(sendingEmailService).sendDomainApprovedEmail(anyString(), anyString(), any());

        customDomainAdminService.approve(ID, null, ADMIN);

        verify(sendingEmailService).sendDomainApprovedEmail("ana@example.com", "go.example.com", DnsRecord.cname("go.example.com", "domains.hrva.cc"));
        assertThat(steps).containsExactly("begin", "markApproved", "commit", "provision", "email");
    }

    @Test
    void rejectEmailsTheOwner() {
        when(customDomainRepository.markRejected(ID, ADMIN, "Not your domain", NOW)).thenAnswer(invocation -> {
            steps.add("markRejected");
            return 1;
        });
        final var rejected = domain(CustomDomainStatus.REJECTED);
        rejected.setReviewNote("Not your domain");
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(rejected));
        doAnswer(invocation -> steps.add("email")).when(sendingEmailService).sendDomainRejectedEmail(anyString(), anyString(), any());

        customDomainAdminService.reject(ID, "  Not your domain  ", ADMIN);

        verify(sendingEmailService).sendDomainRejectedEmail("ana@example.com", "go.example.com", "Not your domain");
        assertThat(steps).containsExactly("begin", "markRejected", "commit", "email");
    }

    @Test
    void aRejectionWithoutANoteEmailsNoNote() {
        when(customDomainRepository.markRejected(ID, ADMIN, null, NOW)).thenReturn(1);
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.REJECTED)));

        customDomainAdminService.reject(ID, "   ", ADMIN);

        verify(sendingEmailService).sendDomainRejectedEmail("ana@example.com", "go.example.com", null);
    }

    @Test
    void aFailedEmailDoesNotUndoTheReview() {
        doThrow(new TaskRejectedException("mail queue full")).when(sendingEmailService).sendDomainApprovedEmail(anyString(), anyString(), any());
        doThrow(new TaskRejectedException("mail queue full")).when(sendingEmailService).sendDomainRejectedEmail(anyString(), anyString(), any());

        final var approved = customDomainAdminService.approve(ID, null, ADMIN);

        assertThat(approved.domain().status()).isEqualTo(CustomDomainStatus.APPROVED_PROVISIONING);
        verify(customDomainProvisioner).provisionAsync(ID);

        when(customDomainRepository.markRejected(ID, ADMIN, null, NOW)).thenReturn(1);
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.REJECTED)));

        assertThat(customDomainAdminService.reject(ID, null, ADMIN).domain().status()).isEqualTo(CustomDomainStatus.REJECTED);
        verify(sendingEmailService).sendDomainApprovedEmail(anyString(), anyString(), any());
        verify(sendingEmailService).sendDomainRejectedEmail(anyString(), anyString(), any());
    }

    @Test
    void aRefusedReviewEmailsNobody() {
        when(customDomainRepository.findById(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.ACTIVE)));

        assertThatThrownBy(() -> customDomainAdminService.approve(ID, null, ADMIN)).isInstanceOf(CustomDomainConflictException.class);
        assertThatThrownBy(() -> customDomainAdminService.reject(ID, null, ADMIN)).isInstanceOf(CustomDomainConflictException.class);

        when(customDomainRepository.findById(ID)).thenReturn(Optional.of(domain(CustomDomainStatus.PENDING_REVIEW)));
        when(customDomainRepository.markRejected(ID, ADMIN, null, NOW)).thenReturn(0);

        assertThatThrownBy(() -> customDomainAdminService.reject(ID, null, ADMIN)).isInstanceOf(CustomDomainConflictException.class);
        verifyNoInteractions(sendingEmailService);
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
        final var provisioner = new CustomDomainProvisioner(customDomainRepository, cloudflareClient, eventPublisher, TransactionOperations.withoutTransaction(),
                appProperties, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
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
        final var provisioner = new CustomDomainProvisioner(customDomainRepository, cloudflareClient, eventPublisher, TransactionOperations.withoutTransaction(),
                appProperties, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
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
    void theSummaryShowsTheLastQuotaReading() {
        when(cloudflareTokenCheck.current()).thenReturn(new TokenState(CloudflareTokenStatus.OK, NOW, null, 4));
        when(customDomainReconciler.lastQuota()).thenReturn(Optional.of(new QuotaReading(4, 50_000, NOW)));

        assertThat(customDomainAdminService.summary().cloudflareQuota()).isEqualTo(new DomainSummaryResponse.CloudflareQuota(4, 50_000));
    }

    @Test
    void retryChecksAWaitingOrFailingDomainNow() {
        for (final var problem : List.of(DomainProblem.CNAME_REMOVED, DomainProblem.DNS_TIMED_OUT, DomainProblem.CERTIFICATE_TIMED_OUT)) {
            retrying(CustomDomainStatus.ERROR, problem, null);

            assertThat(customDomainAdminService.retry(ID).domain().id()).isEqualTo(ID);
        }
        retrying(CustomDomainStatus.AWAITING_DNS, DomainProblem.DNS_NOT_FOUND, "cf-1");
        customDomainAdminService.retry(ID);

        verify(customDomainLifecycle, times(4)).checkNow(ID);
        verifyNoInteractions(customDomainProvisioner, customDomainCapLock);
        verify(customDomainRepository, never()).markRetrying(anyLong(), any());
    }

    @Test
    void retrySetsUpADomainStillBeingRetriedAtOnce() {
        retrying(CustomDomainStatus.APPROVED_PROVISIONING, DomainProblem.PROVISIONING_FAILED, null);

        customDomainAdminService.retry(ID);

        verify(customDomainProvisioner).provision(ID);
        verify(customDomainRepository, never()).markRetrying(anyLong(), any());
        verifyNoInteractions(customDomainLifecycle, customDomainCapLock, cloudflareTokenCheck);
    }

    @Test
    void retrySetsUpAFailedDomainAgainAfterMovingItBack() {
        retrying(CustomDomainStatus.ERROR, DomainProblem.PROVISIONING_FAILED, "cf-gone");
        when(customDomainRepository.markRetrying(ID, NOW)).thenAnswer(invocation -> {
            steps.add("markRetrying");
            return 1;
        });
        doAnswer(invocation -> {
            steps.add("provision");
            return LifecycleOutcome.DONE;
        }).when(customDomainProvisioner).provision(ID);

        customDomainAdminService.retry(ID);

        assertThat(steps).containsSubsequence("begin", "markRetrying", "commit", "provision");
        verify(eventPublisher).publishEvent(new CustomDomainChangedEvent(ID));
        final var order = inOrder(cloudflareTokenCheck, customDomainCapLock, customDomainRepository);
        order.verify(cloudflareTokenCheck).check();
        order.verify(customDomainCapLock).lock();
        order.verify(customDomainRepository).countByStatus(CustomDomainStatus.APPROVED_PROVISIONING);
        order.verify(customDomainRepository).markRetrying(ID, NOW);
        verifyNoInteractions(customDomainLifecycle);
    }

    @Test
    void retryOfAFailedDomainStillHoldingAHostnameNeedsTheFreeAllowanceButIsNotCountedTwiceAgainstTheCap() {
        retrying(CustomDomainStatus.ERROR, DomainProblem.PROVISIONING_FAILED, "cf-gone");
        when(customDomainRepository.markRetrying(ID, NOW)).thenReturn(1);
        when(customDomainRepository.countSlotsInUse()).thenReturn(90L);

        customDomainAdminService.retry(ID);

        verify(customDomainProvisioner).provision(ID);
        when(customDomainRepository.countSlotsInUse()).thenReturn(91L);
        assertThatThrownBy(() -> customDomainAdminService.retry(ID)).isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("Cap reached: 90 of 90 custom domains are in use. Remove one or raise the cap (APP_DOMAINS_MAX_ACTIVE).");

        when(customDomainRepository.countSlotsInUse()).thenReturn(3L);
        when(customDomainRepository.countByStatus(CustomDomainStatus.APPROVED_PROVISIONING)).thenReturn(1L);
        when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.OK, 99));
        assertThatThrownBy(() -> customDomainAdminService.retry(ID)).isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("Cloudflare already has 100 custom hostnames and 100 are free. Delete one here or in Cloudflare first.");

        verify(customDomainRepository, times(1)).markRetrying(ID, NOW);
        verify(customDomainProvisioner, times(1)).provision(ID);
    }

    @Test
    void retryOfAFailedDomainWithoutAHostnameNeedsRoomUnderTheCapAndTheFreeAllowance() {
        retrying(CustomDomainStatus.ERROR, DomainProblem.PROVISIONING_FAILED, null);
        when(customDomainRepository.markRetrying(ID, NOW)).thenReturn(1);

        customDomainAdminService.retry(ID);

        final var order = inOrder(cloudflareTokenCheck, customDomainCapLock, customDomainRepository, customDomainProvisioner);
        order.verify(cloudflareTokenCheck).check();
        order.verify(customDomainCapLock).lock();
        order.verify(customDomainRepository).countSlotsInUse();
        order.verify(customDomainRepository).markRetrying(ID, NOW);
        order.verify(customDomainProvisioner).provision(ID);

        when(customDomainRepository.countSlotsInUse()).thenReturn(90L);
        assertThatThrownBy(() -> customDomainAdminService.retry(ID)).isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("Cap reached: 90 of 90 custom domains are in use. Remove one or raise the cap (APP_DOMAINS_MAX_ACTIVE).");

        when(customDomainRepository.countSlotsInUse()).thenReturn(3L);
        when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.OK, 100));
        assertThatThrownBy(() -> customDomainAdminService.retry(ID)).isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("Cloudflare already has 100 custom hostnames and 100 are free. Delete one here or in Cloudflare first.");

        when(cloudflareTokenCheck.check()).thenReturn(token(CloudflareTokenStatus.UNREACHABLE, null));
        assertThatThrownBy(() -> customDomainAdminService.retry(ID)).isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("Cloudflare isn't set up: Cloudflare can't be reached. Check CLOUDFLARE_API_TOKEN and CLOUDFLARE_ZONE_ID.");

        verify(customDomainRepository, times(1)).markRetrying(ID, NOW);
        verify(customDomainProvisioner, times(1)).provision(ID);
    }

    @Test
    void retryOnlyForErrorOrAwaitingDns() {
        for (final var status : List.of(CustomDomainStatus.PENDING_REVIEW, CustomDomainStatus.APPROVED_PROVISIONING, CustomDomainStatus.ACTIVE,
                CustomDomainStatus.REJECTED, CustomDomainStatus.DISABLED, CustomDomainStatus.DELETED)) {
            retrying(status, null, "cf-1");

            assertThatThrownBy(() -> customDomainAdminService.retry(ID)).isInstanceOf(CustomDomainConflictException.class)
                    .hasMessage("There's nothing to retry for this domain.");
        }
        when(customDomainRepository.findById(ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> customDomainAdminService.retry(ID)).isInstanceOf(CustomDomainNotFoundException.class)
                .hasMessage(NOT_FOUND);

        verifyNoInteractions(customDomainProvisioner, customDomainLifecycle, cloudflareTokenCheck);
    }

    @Test
    void aRetryThatLostARaceSetsNothingUp() {
        retrying(CustomDomainStatus.ERROR, DomainProblem.PROVISIONING_FAILED, "cf-gone");
        when(customDomainRepository.markRetrying(ID, NOW)).thenReturn(0);

        assertThatThrownBy(() -> customDomainAdminService.retry(ID)).isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("There's nothing to retry for this domain.");

        verifyNoInteractions(customDomainProvisioner, eventPublisher);
    }

    @Test
    void disableNeedsANote() {
        assertThat(VALIDATOR.validate(DomainDisableDto.builder().build())).extracting(violation -> violation.getMessage())
                .containsExactly("Reason is required");
        assertThat(VALIDATOR.validate(DomainDisableDto.builder().note("   ").build())).extracting(violation -> violation.getMessage())
                .containsExactly("Reason is required");
        assertThat(VALIDATOR.validate(DomainDisableDto.builder().note("x".repeat(501)).build())).extracting(violation -> violation.getMessage())
                .containsExactly("Keep it to 500 characters or fewer");
        assertThat(VALIDATOR.validate(DomainDisableDto.builder().note("  " + "x".repeat(500) + "  ").build())).isEmpty();

        assertThatThrownBy(() -> customDomainAdminService.disable(ID, "  "))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        exception -> assertThat(exception.getFieldErrors()).containsExactly(Map.entry("note", "Reason is required")));
        verify(customDomainRepository, never()).markDisabled(anyLong(), any(), any(), any(), any());
    }

    @Test
    void disableStopsPollingAndKeepsTheSlot() {
        final var live = domain(CustomDomainStatus.ACTIVE);
        live.setCloudflareId("cf-1");
        final var off = domain(CustomDomainStatus.DISABLED);
        off.setCloudflareId("cf-1");
        off.setDisabledReason(DomainDisableReason.ADMIN);
        off.setDisabledNote("Phishing");
        when(customDomainRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(live));
        when(customDomainRepository.markDisabled(ID, CustomDomainStatus.ACTIVE, DomainDisableReason.ADMIN, "Phishing", NOW)).thenAnswer(invocation -> {
            steps.add("markDisabled");
            return 1;
        });
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(off));

        final var response = customDomainAdminService.disable(ID, "  Phishing  ");

        assertThat(response.domain().status()).isEqualTo(CustomDomainStatus.DISABLED);
        assertThat(response.cloudflareId()).isEqualTo("cf-1");
        assertThat(response.disabledReason()).isEqualTo(DomainDisableReason.ADMIN);
        assertThat(steps).containsSubsequence("begin", "markDisabled", "commit");
        verify(eventPublisher).publishEvent(new CustomDomainChangedEvent(ID));
        verify(eventPublisher).publishEvent(new DomainDisabled(ID, "go.example.com", DomainDisableReason.ADMIN, "Phishing"));
        verify(customDomainRepository, never()).markDeleted(anyLong(), any(), any());
        verify(customDomainRepository, never()).clearDeletePending(anyLong(), any());
        verifyNoInteractions(cloudflareClient, customDomainProvisioner, customDomainLifecycle, customDomainRemoval);
    }

    @Test
    void enableChecksAgainAtOnce() {
        final var off = domain(CustomDomainStatus.DISABLED);
        off.setCloudflareId("cf-1");
        off.setDisabledReason(DomainDisableReason.ADMIN);
        when(customDomainRepository.findByIdForUpdate(ID)).thenReturn(Optional.of(off));
        when(customDomainRepository.markEnabled(ID, NOW)).thenAnswer(invocation -> {
            steps.add("markEnabled");
            return 1;
        });
        doAnswer(invocation -> {
            steps.add("checkNow");
            return LifecycleOutcome.DONE;
        }).when(customDomainLifecycle).checkNow(ID);
        final var waiting = domain(CustomDomainStatus.AWAITING_DNS);
        waiting.setNextCheckAt(NOW);
        when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(waiting));

        final var response = customDomainAdminService.enable(ID);

        assertThat(response.domain().status()).isEqualTo(CustomDomainStatus.AWAITING_DNS);
        assertThat(steps).containsSubsequence("begin", "markEnabled", "commit", "checkNow");
        verify(eventPublisher).publishEvent(new CustomDomainChangedEvent(ID));
        verify(customDomainRepository, never()).markEnabledWithoutHostname(anyLong(), any());
        verifyNoInteractions(customDomainProvisioner, cloudflareTokenCheck);
    }

    @Test
    void deleteGoesThroughTheSharedRemovalAsAnAdmin() {
        final var summary = new DomainRemovalResponse(2, List.of(new DomainRemovalResponse.DomainRename("menu", "k3J9x")), 0);
        when(customDomainRemoval.remove(ID, DomainLinkMode.MOVE, new Actor(ADMIN, true))).thenReturn(summary);

        assertThat(customDomainAdminService.delete(ID, DomainLinkMode.MOVE, ADMIN)).isEqualTo(summary);

        verifyNoInteractions(cloudflareClient, cloudflareTokenCheck);
    }

    @Test
    void reconcileAnswersTheRunItMade() {
        appProperties.getDomains().setIgnoredHostnames(List.of("test.karlo.cc"));
        when(customDomainReconciler.reconcile()).thenReturn(Optional.of(new ReconcileRun(NOW, 4, 3, List.of("stranger.example.net"),
                List.of(new MissingDomain(9L, "x.example.org")), null)));

        assertThat(customDomainAdminService.reconcile()).isEqualTo(new ReconcileResponse(NOW, 4, 3, List.of("stranger.example.net"),
                List.of(new ReconcileResponse.MissingDomain(9L, "x.example.org")), List.of("test.karlo.cc"), null));
        verifyNoInteractions(customDomainReconcileRepository);
    }

    @Test
    void aReconcileAlreadyRunningAnswersTheLastRun() {
        when(customDomainReconciler.reconcile()).thenReturn(Optional.empty());
        when(customDomainReconcileRepository.newest()).thenReturn(Optional.of(new ReconcileRun(NOW.minusHours(3), 4, 4, List.of(), List.of(), null)));

        assertThat(customDomainAdminService.reconcile().ranAt()).isEqualTo(NOW.minusHours(3));

        when(customDomainReconcileRepository.newest()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> customDomainAdminService.reconcile()).isInstanceOf(CustomDomainConflictException.class)
                .hasMessage("A reconcile is already running. Try again in a minute.");
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

    private void retrying(final CustomDomainStatus status, final DomainProblem problem, final String cloudflareId) {
        final var row = domain(status);
        row.setProblem(problem);
        row.setCloudflareId(cloudflareId);
        row.setReviewedAt(NOW.minusDays(2));
        when(customDomainRepository.findById(ID)).thenReturn(Optional.of(row));
        lenient().when(customDomainRepository.findWithOwner(ID)).thenReturn(Optional.of(row));
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
