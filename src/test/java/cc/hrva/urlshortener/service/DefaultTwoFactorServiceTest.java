package cc.hrva.urlshortener.service;

import java.util.Collections;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.model.TwoFactorRecoveryCode;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.Totp;
import cc.hrva.urlshortener.service.impl.DefaultTwoFactorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultTwoFactorServiceTest {

    private TwoFactorService twoFactorService;

    @Mock
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AppProperties appProperties;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TwoFactorRecoveryCodeRepository recoveryCodeRepository;

    @BeforeEach
    void setUp() {
        this.twoFactorService = new DefaultTwoFactorService(userService, userRepository, appProperties,
                passwordEncoder, recoveryCodeRepository);
    }

    @Test
    void shouldSetupForLocalUser() {
        final var user = User.builder().id(1L).email("u@example.com").authProvider("local").build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(appProperties.getAppName()).thenReturn("hrva.cc");

        final var response = twoFactorService.setup();

        assertThat(response.secret()).isNotBlank();
        assertThat(response.otpauthUri()).contains("otpauth://totp/");
        assertThat(user.getTwoFactorSecret()).isEqualTo(response.secret());
    }

    @Test
    void refusesToRestartSetupWhileTwoFactorIsOn() {
        final var user = User.builder().id(1L).email("u@example.com").authProvider("local")
                .twoFactorEnabled(true).twoFactorSecret("EXISTINGSECRET").build();
        when(userService.getUserFromToken()).thenReturn(user);

        assertThatThrownBy(() -> twoFactorService.setup())
                .isInstanceOf(ApiException.class)
                .hasMessage("Two-factor authentication is already enabled. Disable it first.");
        assertThat(user.getTwoFactorEnabled()).isTrue();
        assertThat(user.getTwoFactorSecret()).isEqualTo("EXISTINGSECRET");
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldRejectSetupForOauthUser() {
        final var user = User.builder().id(1L).email("g@example.com").authProvider("google").build();
        when(userService.getUserFromToken()).thenReturn(user);

        assertThatThrownBy(() -> twoFactorService.setup()).isInstanceOf(ApiException.class);
    }

    @Test
    void shouldEnableWithValidCode() {
        final var secret = Totp.generateSecret();
        final var user = User.builder().id(1L).email("u@example.com").authProvider("local").twoFactorSecret(secret).build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");

        final var response = twoFactorService.enable(Totp.currentCode(secret));

        assertThat(user.getTwoFactorEnabled()).isTrue();
        assertThat(response.recoveryCodes()).hasSize(10);
        verify(recoveryCodeRepository).deleteByUser(user);
        verify(recoveryCodeRepository).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void shouldFailEnableWithInvalidCode() {
        final var secret = Totp.generateSecret();
        final var user = User.builder().id(1L).authProvider("local").twoFactorSecret(secret).build();
        when(userService.getUserFromToken()).thenReturn(user);

        assertThatThrownBy(() -> twoFactorService.enable("000000"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid verification code");
    }

    @Test
    void shouldDisableWithValidTotp() {
        final var secret = Totp.generateSecret();
        final var user = User.builder().id(1L).authProvider("local").twoFactorEnabled(true).twoFactorSecret(secret).build();
        when(userService.getUserFromToken()).thenReturn(user);
        lenient().when(recoveryCodeRepository.findByUserAndUsedFalse(user)).thenReturn(Collections.emptyList());

        twoFactorService.disable(Totp.currentCode(secret));

        assertThat(user.getTwoFactorEnabled()).isFalse();
        assertThat(user.getTwoFactorSecret()).isNull();
        verify(recoveryCodeRepository).deleteByUser(user);
    }

    @Test
    void shouldVerifyLoginWithRecoveryCode() {
        final var user = User.builder().id(1L).twoFactorSecret(Totp.generateSecret()).build();
        final var recovery = TwoFactorRecoveryCode.builder().id(1L).user(user).codeHash("hash").used(false).build();
        when(recoveryCodeRepository.findByUserAndUsedFalse(user)).thenReturn(Collections.singletonList(recovery));
        when(passwordEncoder.matches("ABCD-EFGH", "hash")).thenReturn(true);

        final var result = twoFactorService.verifyLoginCode(user, "ABCD-EFGH");

        assertThat(result).isTrue();
        assertThat(recovery.isUsed()).isTrue();
        verify(recoveryCodeRepository).save(recovery);
    }

    @Test
    void shouldVerifyLoginWithTotp() {
        final var secret = Totp.generateSecret();
        final var user = User.builder().id(1L).twoFactorSecret(secret).build();

        assertThat(twoFactorService.verifyLoginCode(user, Totp.currentCode(secret))).isTrue();
    }
}
