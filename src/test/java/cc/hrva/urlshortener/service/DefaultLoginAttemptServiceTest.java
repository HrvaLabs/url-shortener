package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.impl.DefaultLoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLoginAttemptServiceTest {

    private LoginAttemptService loginAttemptService;

    @Mock
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        when(appProperties.getMaxLoginAttempts()).thenReturn(3L);
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

}
