package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LoginAttemptResponse;
import cc.hrva.urlshortener.exception.InvalidSortException;
import cc.hrva.urlshortener.service.impl.DefaultLoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DefaultLoginAttemptServiceTest {

    private LoginAttemptService loginAttemptService;

    @Mock
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        lenient().when(appProperties.getMaxLoginAttempts()).thenReturn(3L);
        this.loginAttemptService = new DefaultLoginAttemptService(appProperties);
    }

    @Test
    void shouldNotBeBlockedInitially() {
        assertThat(loginAttemptService.isBlocked("127.0.0.1")).isFalse();
    }

    @Test
    void shouldBlockAfterMaxAttempts() {
        final var ip = "127.0.0.1";

        loginAttemptService.loginFailed(ip);
        loginAttemptService.loginFailed(ip);
        loginAttemptService.loginFailed(ip);

        assertThat(loginAttemptService.isBlocked(ip)).isTrue();
    }

    @Test
    void shouldNotBlockBeforeMaxAttempts() {
        final var ip = "127.0.0.1";

        loginAttemptService.loginFailed(ip);
        loginAttemptService.loginFailed(ip);

        assertThat(loginAttemptService.isBlocked(ip)).isFalse();
    }

    @Test
    void shouldResetOnLoginSuccess() {
        final var ip = "127.0.0.1";

        loginAttemptService.loginFailed(ip);
        loginAttemptService.loginFailed(ip);
        loginAttemptService.loginFailed(ip);
        assertThat(loginAttemptService.isBlocked(ip)).isTrue();

        loginAttemptService.loginSucceeded(ip);
        assertThat(loginAttemptService.isBlocked(ip)).isFalse();
    }

    @Test
    void shouldTrackDifferentIpsSeparately() {
        final var ip1 = "127.0.0.1";
        final var ip2 = "127.0.0.2";

        loginAttemptService.loginFailed(ip1);
        loginAttemptService.loginFailed(ip1);
        loginAttemptService.loginFailed(ip1);

        assertThat(loginAttemptService.isBlocked(ip1)).isTrue();
        assertThat(loginAttemptService.isBlocked(ip2)).isFalse();
    }

    @Test
    void aFloodOfNewKeysKeepsAtMostTenThousandOfThem() {
        for (var client = 0; client < 10_500; client++) {
            loginAttemptService.loginFailed("10.0." + client);
        }
        loginAttemptService.loginFailed("10.0.10499");
        loginAttemptService.loginFailed("10.0.10499");

        assertThat(loginAttemptService.getLoginAttempts()).hasSizeLessThanOrEqualTo(10_000);
        assertThat(loginAttemptService.isBlocked("10.0.10499")).isTrue();
    }

    @Test
    void attemptsComeMostFirstWithTheKeyBreakingTies() {
        failed("10.0.0.2", 2);
        failed("10.0.0.1", 2);
        failed("2fa:ana@example.com", 4);
        failed("10.0.0.3", 1);

        final var page = loginAttemptService.attempts(null, PageRequest.of(0, 20));

        assertThat(page.getContent()).containsExactly(
                new LoginAttemptResponse("2fa:ana@example.com", 4),
                new LoginAttemptResponse("10.0.0.1", 2),
                new LoginAttemptResponse("10.0.0.2", 2),
                new LoginAttemptResponse("10.0.0.3", 1));
    }

    @Test
    void attemptsCanBeSortedFewestFirstOrByKey() {
        failed("10.0.0.2", 3);
        failed("10.0.0.1", 1);
        failed("2fa:ana@example.com", 2);

        assertThat(loginAttemptService.attempts(null, PageRequest.of(0, 20, Sort.by(Sort.Order.asc("attempts")))).getContent())
                .extracting(LoginAttemptResponse::key).containsExactly("10.0.0.1", "2fa:ana@example.com", "10.0.0.2");
        assertThat(loginAttemptService.attempts(null, PageRequest.of(0, 20, Sort.by(Sort.Order.desc("key")))).getContent())
                .extracting(LoginAttemptResponse::key).containsExactly("2fa:ana@example.com", "10.0.0.2", "10.0.0.1");
    }

    @Test
    void aPageOfAttemptsHoldsItsShareAndTheTotal() {
        for (var client = 1; client <= 5; client++) {
            failed("10.0.0." + client, client);
        }

        final var page = loginAttemptService.attempts(null, PageRequest.of(1, 2));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).extracting(LoginAttemptResponse::key).containsExactly("10.0.0.3", "10.0.0.2");
    }

    @Test
    void theSearchKeepsTheKeysThatContainItInAnyCase() {
        failed("2fa:Ana@Example.com", 1);
        failed("10.0.0.1", 1);
        failed("192.168.0.1", 1);

        assertThat(loginAttemptService.attempts(" ana@ ", PageRequest.of(0, 20)).getContent())
                .extracting(LoginAttemptResponse::key).containsExactly("2fa:Ana@Example.com");
        assertThat(loginAttemptService.attempts("0.1", PageRequest.of(0, 20)).getContent())
                .extracting(LoginAttemptResponse::key).containsExactly("10.0.0.1", "192.168.0.1");
    }

    @Test
    void attemptsRefuseToSortByAFieldOffTheList() {
        assertThatThrownBy(() -> loginAttemptService.attempts(null, PageRequest.of(0, 20, Sort.by("blocked"))))
                .isInstanceOf(InvalidSortException.class)
                .hasMessage("Sort must be attempts or key");
    }

    private void failed(final String key, final int times) {
        for (var attempt = 0; attempt < times; attempt++) {
            loginAttemptService.loginFailed(key);
        }
    }

}
