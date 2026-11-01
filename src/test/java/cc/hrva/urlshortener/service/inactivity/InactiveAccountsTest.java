package cc.hrva.urlshortener.service.inactivity;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppDto;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.SendingEmailService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InactiveAccountsTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.of(2026, 10, 30, 10, 0);
    private static final long DAYS = 1825;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private ConnectedAppsService connectedAppsService;

    @Mock
    private SendingEmailService sendingEmailService;

    private InactiveAccounts inactiveAccounts;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setDeactivateUserAccountAfterDays(DAYS);
        inactiveAccounts = new InactiveAccounts(userRepository, apiKeyRepository, connectedAppsService, sendingEmailService, appProperties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(connectedAppsService.findConnectedApps(any())).thenReturn(List.of());
        lenient().when(apiKeyRepository.findLastUseByOwnerId(any())).thenReturn(Optional.empty());
        lenient().when(userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(any())).thenReturn(List.of());
    }

    @Test
    void anAccountThatReachesTheWarningWindowIsWarnedOnceAndNotDeactivated() {
        final var lastLogin = NOW_LOCAL.minusDays(DAYS - 30);
        final var user = idle(1L, lastLogin);
        candidates(user);
        when(userRepository.claimInactivityWarning(1L, NOW_LOCAL, lastLogin)).thenReturn(1);

        final var due = inactiveAccounts.warnAndSelectForDeactivation();

        assertThat(due).isEmpty();
        verify(sendingEmailService).sendInactivityWarningEmail(user, LocalDate.of(2026, 11, 29));
    }

    @Test
    void theWarningNamesThirtyDaysFromNowWhenTheAccountIsAlreadyPastTheLimit() {
        final var lastLogin = NOW_LOCAL.minusDays(DAYS + 100);
        final var user = idle(1L, lastLogin);
        candidates(user);
        when(userRepository.claimInactivityWarning(1L, NOW_LOCAL, lastLogin)).thenReturn(1);

        final var due = inactiveAccounts.warnAndSelectForDeactivation();

        assertThat(due).isEmpty();
        verify(sendingEmailService).sendInactivityWarningEmail(user, LocalDate.of(2026, 11, 29));
    }

    @Test
    void aWarningAnotherRunAlreadyClaimedIsNotSentAgain() {
        final var lastLogin = NOW_LOCAL.minusDays(DAYS - 10);
        candidates(idle(1L, lastLogin));
        when(userRepository.claimInactivityWarning(1L, NOW_LOCAL, lastLogin)).thenReturn(0);

        inactiveAccounts.warnAndSelectForDeactivation();

        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void anAccountWarnedForThisIdlePeriodIsNotWarnedAgain() {
        final var lastLogin = NOW_LOCAL.minusDays(DAYS - 10);
        candidates(idleBuilder(1L, lastLogin).inactivityWarnedAt(NOW_LOCAL.minusDays(19)).build());

        final var due = inactiveAccounts.warnAndSelectForDeactivation();

        assertThat(due).isEmpty();
        verify(userRepository, never()).claimInactivityWarning(any(), any(), any());
        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void anAccountIsDeactivatedOnlyThirtyDaysAfterItsWarning() {
        final var lastLogin = NOW_LOCAL.minusDays(DAYS + 5);
        final var warnedTooRecently = idleBuilder(1L, lastLogin).inactivityWarnedAt(NOW_LOCAL.minusDays(29)).build();
        final var warnedLongEnoughAgo = idleBuilder(2L, lastLogin).inactivityWarnedAt(NOW_LOCAL.minusDays(30)).build();
        candidates(warnedTooRecently, warnedLongEnoughAgo);

        final var due = inactiveAccounts.warnAndSelectForDeactivation();

        assertThat(due).containsExactly(warnedLongEnoughAgo);
        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void anAccountNeverWarnedIsWarnedFirstEvenWhenItIsPastTheLimit() {
        final var lastLogin = NOW_LOCAL.minusDays(DAYS + 400);
        final var user = idle(1L, lastLogin);
        candidates(user);
        when(userRepository.claimInactivityWarning(1L, NOW_LOCAL, lastLogin)).thenReturn(1);

        assertThat(inactiveAccounts.warnAndSelectForDeactivation()).isEmpty();
    }

    @Test
    void aWarningFromAnEarlierIdlePeriodDoesNotCount() {
        final var lastLogin = NOW_LOCAL.minusDays(DAYS + 5);
        final var user = idleBuilder(1L, lastLogin).inactivityWarnedAt(lastLogin.minusDays(400)).build();
        candidates(user);
        when(userRepository.claimInactivityWarning(1L, NOW_LOCAL, lastLogin)).thenReturn(1);

        assertThat(inactiveAccounts.warnAndSelectForDeactivation()).isEmpty();
        verify(sendingEmailService).sendInactivityWarningEmail(user, LocalDate.of(2026, 11, 29));
    }

    @Test
    void anApiKeyUseCountsAsActivity() {
        final var user = idle(1L, NOW_LOCAL.minusDays(DAYS + 5));
        candidates(user);
        when(apiKeyRepository.findLastUseByOwnerId(1L)).thenReturn(Optional.of(NOW_LOCAL.minusDays(40)));

        assertThat(inactiveAccounts.warnAndSelectForDeactivation()).isEmpty();
        verifyNoInteractions(sendingEmailService);
        verify(userRepository, never()).claimInactivityWarning(any(), any(), any());
    }

    @Test
    void anMcpTokenCountsAsActivity() {
        final var user = idle(1L, NOW_LOCAL.minusDays(DAYS + 5));
        candidates(user);
        when(connectedAppsService.findConnectedApps("user-1@example.com")).thenReturn(List.of(
                new ConnectedAppDto("old", "Old", NOW.minusSeconds(86400L * 3000), NOW.minusSeconds(86400L * 2000)),
                new ConnectedAppDto("new", "New", NOW.minusSeconds(86400L * 500), NOW.minusSeconds(86400L * 3))));

        assertThat(inactiveAccounts.warnAndSelectForDeactivation()).isEmpty();
        verifyNoInteractions(sendingEmailService);
        verify(userRepository, never()).claimInactivityWarning(any(), any(), any());
    }

    @Test
    void aConnectedAppThatNeverGotATokenIsNotActivity() {
        final var lastLogin = NOW_LOCAL.minusDays(DAYS - 10);
        final var user = idle(1L, lastLogin);
        candidates(user);
        when(connectedAppsService.findConnectedApps("user-1@example.com"))
                .thenReturn(List.of(new ConnectedAppDto("x", "X", NOW.minusSeconds(86400L * 3000), null)));
        when(userRepository.claimInactivityWarning(1L, NOW_LOCAL, lastLogin)).thenReturn(1);

        inactiveAccounts.warnAndSelectForDeactivation();

        verify(sendingEmailService).sendInactivityWarningEmail(user, LocalDate.of(2026, 11, 29));
    }

    @Test
    void nobodyIsDueWhenNobodyIsIdle() {
        assertThat(inactiveAccounts.warnAndSelectForDeactivation()).isEmpty();
        verifyNoInteractions(sendingEmailService, connectedAppsService);
    }

    private void candidates(final User... users) {
        when(userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(NOW_LOCAL.minusDays(DAYS - 30))).thenReturn(List.of(users));
    }

    private static User idle(final long id, final LocalDateTime lastLogin) {
        return idleBuilder(id, lastLogin).build();
    }

    private static User.UserBuilder idleBuilder(final long id, final LocalDateTime lastLogin) {
        return User.builder().id(id).email("user-" + id + "@example.com").active(true).lastLogin(lastLogin);
    }

}
