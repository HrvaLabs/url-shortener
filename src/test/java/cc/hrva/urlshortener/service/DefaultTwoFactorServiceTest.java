package cc.hrva.urlshortener.service;

import java.util.Collections;
import java.util.List;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.model.TwoFactorRecoveryCode;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.Totp;
import cc.hrva.urlshortener.service.impl.DefaultTwoFactorService;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import cc.hrva.urlshortener.service.twostep.RecoveryCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultTwoFactorServiceTest {

    private static final List<String> TEN_CODES = List.of("ABCD-EF23", "GHJK-LM45", "NPQR-ST67", "UVWX-YZ89", "AB23-CD45",
            "EF67-GH89", "JK23-LM45", "NP67-QR89", "ST23-UV45", "WX67-YZ89");

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

    @Mock
    private RecoveryCodes recoveryCodes;

    @Mock
    private AccountLock accountLock;

    @BeforeEach
    void setUp() {
        this.twoFactorService = new DefaultTwoFactorService(userService, userRepository, appProperties,
                passwordEncoder, recoveryCodeRepository, recoveryCodes, accountLock);
        lenient().when(accountLock.lock(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
    void refusesSetupWhileAnAppIsSetUp() {
        final var user = User.builder().id(1L).email("u@example.com").authProvider("local")
                .twoFactorEnabled(true).twoFactorSecret("EXISTINGSECRET").build();
        when(userService.getUserFromToken()).thenReturn(user);

        assertThatThrownBy(() -> twoFactorService.setup())
                .isInstanceOf(ApiException.class)
                .hasMessage("An authenticator app is already set up. Remove it first.");
        assertThat(user.getTwoFactorEnabled()).isTrue();
        assertThat(user.getTwoFactorSecret()).isEqualTo("EXISTINGSECRET");
        verify(userRepository, never()).save(any());
    }

    @Test
    void aGoogleAccountCanSetUpAnAuthenticatorApp() {
        final var user = User.builder().id(1L).email("g@example.com").authProvider("google").build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(appProperties.getAppName()).thenReturn("hrva.cc");
        when(recoveryCodes.issueIfNone(user)).thenReturn(TEN_CODES);

        final var setup = twoFactorService.setup();
        final var enabled = twoFactorService.enable(Totp.currentCode(setup.secret()));

        assertThat(user.getTwoFactorSecret()).isEqualTo(setup.secret());
        assertThat(user.getTwoFactorEnabled()).isTrue();
        assertThat(user.getTwoStepEnabled()).isTrue();
        assertThat(enabled.recoveryCodes()).isEqualTo(TEN_CODES);
    }

    @Test
    void enablingTotpTurnsTwoStepOn() {
        final var secret = Totp.generateSecret();
        final var user = User.builder().id(1L).email("u@example.com").authProvider("local").twoFactorSecret(secret)
                .twoFactorEnabled(false).twoStepEnabled(false).build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(recoveryCodes.issueIfNone(user)).thenReturn(TEN_CODES);

        final var response = twoFactorService.enable(Totp.currentCode(secret));

        assertThat(user.getTwoFactorEnabled()).isTrue();
        assertThat(user.getTwoStepEnabled()).isTrue();
        assertThat(response.recoveryCodes()).isEqualTo(TEN_CODES);
        verify(userRepository).save(user);
        verify(recoveryCodeRepository, never()).deleteByUser(any());
    }

    @Test
    void enablingTotpOnAKeysOnlyAccountIssuesNoNewCodes() {
        final var secret = Totp.generateSecret();
        final var user = User.builder().id(1L).email("u@example.com").authProvider("local").twoFactorSecret(secret)
                .twoFactorEnabled(false).twoStepEnabled(true).build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(recoveryCodes.issueIfNone(user)).thenReturn(List.of());

        final var response = twoFactorService.enable(Totp.currentCode(secret));

        assertThat(user.getTwoFactorEnabled()).isTrue();
        assertThat(user.getTwoStepEnabled()).isTrue();
        assertThat(response.recoveryCodes()).isEmpty();
        verify(recoveryCodeRepository, never()).deleteByUser(any());
        verify(recoveryCodeRepository, never()).saveAll(any());
    }

    @Test
    void settingUpLocksTheAccountBeforeCheckingIt() {
        final var fromTheToken = User.builder().id(1L).email("u@example.com").twoFactorEnabled(false).build();
        final var locked = User.builder().id(1L).email("u@example.com").twoFactorEnabled(true).twoFactorSecret("EXISTINGSECRET").build();
        when(userService.getUserFromToken()).thenReturn(fromTheToken);
        when(accountLock.lock(fromTheToken)).thenReturn(locked);

        assertThatThrownBy(() -> twoFactorService.setup())
                .isInstanceOf(ApiException.class)
                .hasMessage("An authenticator app is already set up. Remove it first.");

        assertThat(locked.getTwoFactorSecret()).isEqualTo("EXISTINGSECRET");
        verify(userRepository, never()).save(any());
    }

    @Test
    void enablingLocksTheAccountBeforeCheckingItAndIssuingCodes() {
        final var secret = Totp.generateSecret();
        final var fromTheToken = User.builder().id(1L).email("u@example.com").twoFactorSecret(secret).twoFactorEnabled(false).build();
        final var locked = User.builder().id(1L).email("u@example.com").twoFactorSecret(secret).twoFactorEnabled(false).build();
        when(userService.getUserFromToken()).thenReturn(fromTheToken);
        when(accountLock.lock(fromTheToken)).thenReturn(locked);
        when(recoveryCodes.issueIfNone(locked)).thenReturn(TEN_CODES);

        twoFactorService.enable(Totp.currentCode(secret));

        final var order = inOrder(accountLock, userRepository, recoveryCodes);
        order.verify(accountLock).lock(fromTheToken);
        order.verify(userRepository).save(locked);
        order.verify(recoveryCodes).issueIfNone(locked);
        assertThat(locked.getTwoFactorEnabled()).isTrue();
        assertThat(locked.getTwoStepEnabled()).isTrue();
    }

    @Test
    void anAppSetUpMeanwhileIsSeenThroughTheLock() {
        final var secret = Totp.generateSecret();
        final var fromTheToken = User.builder().id(1L).twoFactorSecret(secret).twoFactorEnabled(false).build();
        final var locked = User.builder().id(1L).twoFactorSecret(secret).twoFactorEnabled(true).build();
        when(userService.getUserFromToken()).thenReturn(fromTheToken);
        when(accountLock.lock(fromTheToken)).thenReturn(locked);

        assertThatThrownBy(() -> twoFactorService.enable(Totp.currentCode(secret)))
                .isInstanceOf(ApiException.class)
                .hasMessage("Two-factor authentication is already enabled");

        verify(userRepository, never()).save(any());
        verify(recoveryCodes, never()).issueIfNone(any());
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
        final var user = User.builder().id(1L).twoFactorEnabled(true).twoFactorSecret(secret).build();

        assertThat(twoFactorService.verifyLoginCode(user, Totp.currentCode(secret))).isTrue();
    }

    @Test
    void verifyLoginCodeSkipsTotpWithoutAnAuthenticatorApp() {
        final var user = User.builder().id(1L).twoFactorEnabled(false).twoFactorSecret(null).twoStepEnabled(true).build();
        when(recoveryCodeRepository.findByUserAndUsedFalse(user)).thenReturn(Collections.emptyList());

        try (final var totp = mockStatic(Totp.class, Mockito.CALLS_REAL_METHODS)) {
            assertThat(twoFactorService.verifyLoginCode(user, "123456")).isFalse();

            totp.verify(() -> Totp.verify(any(), any()), never());
        }
    }

    @Test
    void anUnfinishedAuthenticatorAppSetupDoesNotSignIn() {
        final var secret = Totp.generateSecret();
        final var user = User.builder().id(1L).twoFactorEnabled(false).twoFactorSecret(secret).twoStepEnabled(true).build();
        when(recoveryCodeRepository.findByUserAndUsedFalse(user)).thenReturn(Collections.emptyList());

        assertThat(twoFactorService.verifyLoginCode(user, Totp.currentCode(secret))).isFalse();
    }

    @Test
    void anAuthenticatorAppWithoutASecretFallsBackToRecoveryCodes() {
        final var user = User.builder().id(1L).twoFactorEnabled(true).twoFactorSecret(null).build();
        final var recovery = TwoFactorRecoveryCode.builder().id(1L).user(user).codeHash("hash").used(false).build();
        when(recoveryCodeRepository.findByUserAndUsedFalse(user)).thenReturn(Collections.singletonList(recovery));
        when(passwordEncoder.matches("ABCD-EF23", "hash")).thenReturn(true);

        try (final var totp = mockStatic(Totp.class, Mockito.CALLS_REAL_METHODS)) {
            assertThat(twoFactorService.verifyLoginCode(user, "ABCD-EF23")).isTrue();

            totp.verify(() -> Totp.verify(any(), any()), never());
        }
    }
}
