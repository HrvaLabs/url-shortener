package cc.hrva.urlshortener.service.inactivity;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.SendingEmailService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = InactivityWarningTransactionsTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:inactivity-warnings;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE;LOCK_TIMEOUT=10000",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class InactivityWarningTransactionsTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final LocalDateTime IN_THE_WARNING_WINDOW = LocalDateTime.of(2026, 10, 30, 10, 0).minusDays(1825 - 30);
    private static final long FIRST = 9_101L;
    private static final long SECOND = 9_102L;
    private static final long THIRD = 9_103L;

    @Autowired
    private InactiveAccounts inactiveAccounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private SendingEmailService sendingEmailService;

    @MockitoBean
    private ConnectedAppsService connectedAppsService;

    @MockitoBean
    private AppProperties appProperties;

    @BeforeEach
    void threeIdleAccounts() {
        LongStream.of(FIRST, SECOND, THIRD).forEach(this::idleAccount);
        when(connectedAppsService.findConnectedApps(any())).thenReturn(List.of());
        when(appProperties.getDeactivateUserAccountAfterDays()).thenReturn(1825L);
    }

    @AfterEach
    void removeTheAccounts() {
        jdbcTemplate.update("delete from user_account where id >= ?", FIRST);
    }

    @Test
    void aFailureOnOneAccountLeavesTheOthersClaimedAndWarnedOnceEvenWhenTheCallerRollsBack() {
        when(connectedAppsService.findConnectedApps("idle-9102@example.com")).thenThrow(new IllegalStateException("connected apps unreachable"));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            inactiveAccounts.warnAndSelectForDeactivation();
            throw new IllegalStateException("a later failure in the same run");
        })).hasMessage("a later failure in the same run");

        assertThat(warnedAt(FIRST)).isEqualTo(LocalDateTime.of(2026, 10, 30, 10, 0));
        assertThat(warnedAt(SECOND)).isNull();
        assertThat(warnedAt(THIRD)).isEqualTo(LocalDateTime.of(2026, 10, 30, 10, 0));
        verify(sendingEmailService, times(1)).sendInactivityWarningEmail(argThat(user -> user.getId() == FIRST), eq(LocalDate.of(2026, 11, 29)));
        verify(sendingEmailService, never()).sendInactivityWarningEmail(argThat(user -> user.getId() == SECOND), any());
        verify(sendingEmailService, times(1)).sendInactivityWarningEmail(argThat(user -> user.getId() == THIRD), any());

        reset(sendingEmailService);
        when(connectedAppsService.findConnectedApps(any())).thenReturn(List.of());
        inactiveAccounts.warnAndSelectForDeactivation();

        verify(sendingEmailService, never()).sendInactivityWarningEmail(argThat(user -> user.getId() == FIRST), any());
        verify(sendingEmailService, times(1)).sendInactivityWarningEmail(argThat(user -> user.getId() == SECOND), any());
        verify(sendingEmailService, never()).sendInactivityWarningEmail(argThat(user -> user.getId() == THIRD), any());
    }

    @Test
    void theEmailGoesOutOnlyAfterTheClaimIsCommitted() {
        org.mockito.Mockito.doAnswer(invocation -> {
            final User user = invocation.getArgument(0);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(warnedAt(user.getId())).isNotNull();
            return null;
        }).when(sendingEmailService).sendInactivityWarningEmail(any(), any());

        inactiveAccounts.warnAndSelectForDeactivation();

        verify(sendingEmailService, times(3)).sendInactivityWarningEmail(any(), any());
    }

    @Test
    void candidatesAreReadPageByPageAndEveryOneIsWarned() {
        LongStream.rangeClosed(1, 250).forEach(offset -> idleAccount(FIRST + 1_000 + offset));

        inactiveAccounts.warnAndSelectForDeactivation();

        assertThat(jdbcTemplate.queryForObject("select count(*) from user_account where id >= ? and inactivity_warned_at is not null", Long.class, FIRST))
                .isEqualTo(253L);
        verify(sendingEmailService, times(253)).sendInactivityWarningEmail(any(), any());
    }

    private void idleAccount(final long id) {
        jdbcTemplate.update("insert into user_account (id, email, password, active, last_login) values (?, ?, 'secret-hash', true, ?)",
                id, "idle-" + id + "@example.com", IN_THE_WARNING_WINDOW);
    }

    private LocalDateTime warnedAt(final long id) {
        return jdbcTemplate.queryForObject("select inactivity_warned_at from user_account where id = ?", LocalDateTime.class, id);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UserRepository.class, ApiKeyRepository.class}))
    @Import(InactiveAccounts.class)
    static class TestApplication {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

    }

}
