package cc.hrva.urlshortener.service.twostep;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TwoStepMethodsTest {

    @Mock
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @Mock
    private TwoFactorRecoveryCodeRepository recoveryCodeRepository;

    private TwoStepMethods twoStepMethods;

    @BeforeEach
    void setUp() {
        twoStepMethods = new TwoStepMethods(passkeyCredentialRepository, recoveryCodeRepository);
    }

    @Test
    void listsTotpThenPasskeysThenRecoveryCodes() {
        final var everything = User.builder().id(1L).twoFactorEnabled(true).twoStepEnabled(true).build();
        when(passkeyCredentialRepository.countByUser(everything)).thenReturn(2L);
        when(recoveryCodeRepository.countByUserAndUsedFalse(everything)).thenReturn(9L);

        final var keysOnly = User.builder().id(2L).twoFactorEnabled(false).twoStepEnabled(true).build();
        when(passkeyCredentialRepository.countByUser(keysOnly)).thenReturn(1L);
        when(recoveryCodeRepository.countByUserAndUsedFalse(keysOnly)).thenReturn(0L);

        assertThat(twoStepMethods.of(everything)).containsExactly(TwoStepMethod.TOTP, TwoStepMethod.WEBAUTHN, TwoStepMethod.RECOVERY_CODE);
        assertThat(twoStepMethods.of(keysOnly)).containsExactly(TwoStepMethod.WEBAUTHN);
    }

    @Test
    void anAuthenticatorAppWithRecoveryCodesAndNoPasskeys() {
        final var user = User.builder().id(3L).twoFactorEnabled(true).twoStepEnabled(true).build();
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(0L);
        when(recoveryCodeRepository.countByUserAndUsedFalse(user)).thenReturn(10L);

        assertThat(twoStepMethods.of(user)).containsExactly(TwoStepMethod.TOTP, TwoStepMethod.RECOVERY_CODE);
    }

}
