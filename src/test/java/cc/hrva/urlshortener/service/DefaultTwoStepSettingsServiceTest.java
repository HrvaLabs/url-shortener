package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.TwoFactorCodeDto;
import cc.hrva.urlshortener.dto.TwoStepDisableDto;
import cc.hrva.urlshortener.dto.TwoStepStatusResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.impl.DefaultTwoStepSettingsService;
import cc.hrva.urlshortener.service.passkey.PasskeyAssertions;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonies;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremony;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyStore;
import cc.hrva.urlshortener.service.passkey.PasskeyJson;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import cc.hrva.urlshortener.service.twostep.RecoveryCodes;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.web.webauthn.api.Bytes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultTwoStepSettingsServiceTest {

    private static final String TWO_FACTOR_KEY = "2fa:7";
    private static final String CEREMONY_ID = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n";
    private static final String CREDENTIAL_ID = Bytes.random().toBase64UrlString();
    private static final String CHALLENGE = Bytes.random().toBase64UrlString();
    private static final PasskeyCeremony TURN_OFF_CEREMONY = new PasskeyCeremony(PasskeyCeremonyPurpose.TWO_STEP_DISABLE, CHALLENGE, 7L);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode CREDENTIAL = JSON.createObjectNode().put("rawId", CREDENTIAL_ID);
    private static final List<String> TEN_CODES = List.of("ABCD-EF23", "GHJK-LM45", "NPQR-ST67", "UVWX-YZ89", "AB23-CD45",
            "EF67-GH89", "JK23-LM45", "NP67-QR89", "ST23-UV45", "WX67-YZ89");
    private static final String INVALID_CODE = "Invalid verification code";
    private static final String ONE_FACTOR = "Confirm with a code, a recovery code or a passkey.";
    private static final String OFF = "Two-step verification is off.";
    private static final String LOCKED = "Too many wrong codes for this account. Try again in 24 hours.";
    private static final String LAST_TOTP =
            "Your authenticator app is your only two-step method. Turn off two-step verification, or add a passkey or security key first.";

    @Mock
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @Mock
    private PasskeyCeremonyStore passkeyCeremonyStore;

    @Mock
    private PasskeyAssertions passkeyAssertions;

    @Mock
    private TwoFactorService twoFactorService;

    @Mock
    private RecoveryCodes recoveryCodes;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private AccountLock accountLock;

    private TwoStepSettingsService twoStepSettingsService;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.getPasskeys().setRpId("hrva.cc");
        final var passkeyOptionsFactory = new PasskeyOptionsFactory(appProperties);
        final var passkeyCeremonies = new PasskeyCeremonies(passkeyCeremonyStore, passkeyOptionsFactory, new PasskeyJson(), rateLimiter);
        twoStepSettingsService = new DefaultTwoStepSettingsService(userService, userRepository, passkeyCredentialRepository,
                passkeyCeremonies, passkeyCeremonyStore, passkeyOptionsFactory, passkeyAssertions, twoFactorService,
                recoveryCodes, loginAttemptService, accountLock);
        lenient().when(accountLock.lock(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void turningOnWithKeysOnlyIssuesTenRecoveryCodes() {
        final var user = signedIn(keysOnly(false));
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(1L);
        when(recoveryCodes.issueIfNone(user)).thenReturn(TEN_CODES);

        final var response = twoStepSettingsService.enableWithPasskeys();

        assertThat(response.recoveryCodes()).isEqualTo(TEN_CODES);
        assertThat(user.getTwoStepEnabled()).isTrue();
        assertThat(user.getTwoFactorEnabled()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    void turningOnWithoutAPasskeyIsRefused() {
        final var user = signedIn(keysOnly(false));
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(0L);

        assertThatThrownBy(() -> twoStepSettingsService.enableWithPasskeys())
                .isInstanceOf(ApiException.class)
                .hasMessage("Add a passkey or security key first.");
        assertThat(user.getTwoStepEnabled()).isFalse();
        verify(userRepository, never()).save(any());
        verifyNoInteractions(recoveryCodes);
    }

    @Test
    void turningOnTwiceIsRefused() {
        final var user = signedIn(keysOnly(true));
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(1L);

        assertThatThrownBy(() -> twoStepSettingsService.enableWithPasskeys())
                .isInstanceOf(ApiException.class)
                .hasMessage("Two-step verification is already on.");
        verify(userRepository, never()).save(any());
        verifyNoInteractions(recoveryCodes);
    }

    @Test
    void turningOnKeepsExistingRecoveryCodes() {
        final var user = signedIn(keysOnly(false));
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(2L);
        when(recoveryCodes.issueIfNone(user)).thenReturn(List.of());

        final var response = twoStepSettingsService.enableWithPasskeys();

        assertThat(response.recoveryCodes()).isEmpty();
        assertThat(user.getTwoStepEnabled()).isTrue();
        verify(recoveryCodes, never()).deleteAll(any());
    }

    @Test
    void turningOffWithATotpCodeClearsTotpAndCodesButKeepsPasskeys() {
        final var user = signedIn(withAuthenticatorApp());
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);

        twoStepSettingsService.disable(TwoStepDisableDto.builder().code("123456").build());

        assertThat(user.getTwoStepEnabled()).isFalse();
        assertThat(user.getTwoFactorEnabled()).isFalse();
        assertThat(user.getTwoFactorSecret()).isNull();
        verify(userRepository).save(user);
        verify(recoveryCodes).deleteAll(user);
        verify(passkeyCredentialRepository, never()).deleteByUser(any());
        verify(passkeyCredentialRepository, never()).delete(any());
        verify(loginAttemptService).loginSucceeded(TWO_FACTOR_KEY);
        verify(loginAttemptService, never()).loginFailed(anyString());
        verifyNoInteractions(passkeyCeremonyStore, passkeyAssertions);
    }

    @Test
    void aKeysOnlyAccountTurnsOffWithARecoveryCode() {
        final var user = signedIn(keysOnly(true));
        when(twoFactorService.verifyLoginCode(user, "ABCD-EF23")).thenReturn(true);

        twoStepSettingsService.disable(TwoStepDisableDto.builder().code("ABCD-EF23").build());

        assertThat(user.getTwoStepEnabled()).isFalse();
        verify(recoveryCodes).deleteAll(user);
        verify(loginAttemptService).loginSucceeded(TWO_FACTOR_KEY);
    }

    @Test
    void turningOffWithAPasskeyClearsEverythingButThePasskeys() {
        final var user = signedIn(withAuthenticatorApp());
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(TURN_OFF_CEREMONY));
        when(passkeyAssertions.verify(TURN_OFF_CEREMONY, CREDENTIAL, user)).thenReturn(user);

        twoStepSettingsService.disable(withPasskey());

        assertThat(user.getTwoStepEnabled()).isFalse();
        assertThat(user.getTwoFactorEnabled()).isFalse();
        assertThat(user.getTwoFactorSecret()).isNull();
        verify(userRepository).save(user);
        verify(recoveryCodes).deleteAll(user);
        verify(passkeyCredentialRepository, never()).deleteByUser(any());
        verify(loginAttemptService).loginSucceeded(TWO_FACTOR_KEY);
        verifyNoInteractions(twoFactorService);
    }

    @ParameterizedTest
    @MethodSource("notExactlyOneFactor")
    void turningOffNeedsExactlyOneFactor(final TwoStepDisableDto dto) {
        final var user = signedIn(withAuthenticatorApp());

        assertThatThrownBy(() -> twoStepSettingsService.disable(dto))
                .isInstanceOf(ApiException.class)
                .hasMessage(ONE_FACTOR);
        assertThat(user.getTwoStepEnabled()).isTrue();
        verifyNoInteractions(twoFactorService, passkeyCeremonyStore, passkeyAssertions, recoveryCodes, loginAttemptService, accountLock);
        verify(userRepository, never()).save(any());
    }

    @Test
    void turningOffWhileOffIsRefused() {
        signedIn(keysOnly(false));

        assertThatThrownBy(() -> twoStepSettingsService.disable(TwoStepDisableDto.builder().code("123456").build()))
                .isInstanceOf(ApiException.class)
                .hasMessage(OFF);
        assertThatThrownBy(() -> twoStepSettingsService.disableOptions())
                .isInstanceOf(ApiException.class)
                .hasMessage(OFF);
        verifyNoInteractions(twoFactorService, passkeyCeremonyStore, recoveryCodes, loginAttemptService, accountLock);
    }

    @Test
    void turningOffWhileOffIsRefusedBeforeTheShapeOfTheRequest() {
        signedIn(keysOnly(false));

        assertThatThrownBy(() -> twoStepSettingsService.disable(new TwoStepDisableDto()))
                .isInstanceOf(ApiException.class)
                .hasMessage(OFF);
        verifyNoInteractions(accountLock);
    }

    @Test
    void aWrongCodeLeavesTwoStepOn() {
        final var user = signedIn(withAuthenticatorApp());
        when(twoFactorService.verifyLoginCode(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> twoStepSettingsService.disable(TwoStepDisableDto.builder().code("000000").build()))
                .isInstanceOf(ApiException.class)
                .hasMessage(INVALID_CODE);
        assertThat(user.getTwoStepEnabled()).isTrue();
        assertThat(user.getTwoFactorEnabled()).isTrue();
        assertThat(user.getTwoFactorSecret()).isEqualTo("SECRET");
        verify(userRepository, never()).save(any());
        verifyNoInteractions(recoveryCodes);
    }

    @Test
    void aWrongCodeCountsAgainstTheAccount() {
        final var user = signedIn(withAuthenticatorApp());
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(1L);
        when(twoFactorService.verifyLoginCode(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> twoStepSettingsService.disable(TwoStepDisableDto.builder().code("000000").build()))
                .isInstanceOf(ApiException.class)
                .hasMessage(INVALID_CODE);
        assertThatThrownBy(() -> twoStepSettingsService.removeTotp(new TwoFactorCodeDto("000000")))
                .isInstanceOf(ApiException.class)
                .hasMessage(INVALID_CODE);

        verify(loginAttemptService, times(2)).isBlocked(TWO_FACTOR_KEY);
        verify(loginAttemptService, times(2)).loginFailed(TWO_FACTOR_KEY);
        verify(loginAttemptService, never()).loginSucceeded(anyString());
    }

    @Test
    void aLockedAccountCantTurnTwoStepOff() {
        final var user = signedIn(withAuthenticatorApp());
        when(loginAttemptService.isBlocked(TWO_FACTOR_KEY)).thenReturn(true);

        assertThatThrownBy(() -> twoStepSettingsService.disable(TwoStepDisableDto.builder().code("123456").build()))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(LOCKED);
        assertThatThrownBy(() -> twoStepSettingsService.disable(withPasskey()))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(LOCKED);

        assertThat(user.getTwoStepEnabled()).isTrue();
        verifyNoInteractions(twoFactorService, passkeyCeremonyStore, passkeyAssertions, recoveryCodes);
        verify(loginAttemptService, never()).loginFailed(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    void aLockedAccountCantRemoveItsAuthenticatorApp() {
        final var user = signedIn(withAuthenticatorApp());
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(1L);
        when(loginAttemptService.isBlocked(TWO_FACTOR_KEY)).thenReturn(true);

        assertThatThrownBy(() -> twoStepSettingsService.removeTotp(new TwoFactorCodeDto("123456")))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(LOCKED);

        assertThat(user.getTwoFactorEnabled()).isTrue();
        verifyNoInteractions(twoFactorService);
        verify(userRepository, never()).save(any());
    }

    @Test
    void aRefusedPasskeyCountsAgainstTheAccountAndLeavesTwoStepOn() {
        final var user = signedIn(keysOnly(true));
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.of(TURN_OFF_CEREMONY));
        when(passkeyAssertions.verify(TURN_OFF_CEREMONY, CREDENTIAL, user))
                .thenThrow(new PasskeyVerificationException("That passkey or security key couldn't be verified. Try again."))
                .thenThrow(new ApiException(PasskeyJson.UNREADABLE));

        assertThatThrownBy(() -> twoStepSettingsService.disable(withPasskey()))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage("That passkey or security key couldn't be verified. Try again.");
        assertThatThrownBy(() -> twoStepSettingsService.disable(withPasskey()))
                .isInstanceOf(ApiException.class)
                .hasMessage("That passkey response couldn't be read. Try again.");

        assertThat(user.getTwoStepEnabled()).isTrue();
        verify(loginAttemptService, times(2)).loginFailed(TWO_FACTOR_KEY);
        verify(loginAttemptService, never()).loginSucceeded(anyString());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(recoveryCodes);
    }

    @ParameterizedTest
    @MethodSource("ceremoniesThatDontTurnThisAccountOff")
    void aTurnOffCeremonyThatIsGoneOrNotForThisAccountIsA410(final Optional<PasskeyCeremony> ceremony) {
        final var user = signedIn(keysOnly(true));
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(ceremony);

        assertThatThrownBy(() -> twoStepSettingsService.disable(withPasskey()))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage("This request expired. Try again.");

        assertThat(user.getTwoStepEnabled()).isTrue();
        verifyNoInteractions(passkeyAssertions, recoveryCodes);
        verify(loginAttemptService, never()).loginFailed(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    void disableOptionsStartATurnOffCeremonyForTheAccountsPasskeys() {
        final var user = signedIn(keysOnly(true));
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of(securityKey()));
        final var ceremony = ArgumentCaptor.forClass(PasskeyCeremony.class);
        when(passkeyCeremonyStore.save(ceremony.capture())).thenReturn(CEREMONY_ID);

        final var response = twoStepSettingsService.disableOptions();

        assertThat(response.ceremonyId()).isEqualTo(CEREMONY_ID);
        assertThat(ceremony.getValue().purpose()).isEqualTo(PasskeyCeremonyPurpose.TWO_STEP_DISABLE);
        assertThat(ceremony.getValue().userId()).isEqualTo(7L);
        assertThat(ceremony.getValue().challenge()).hasSize(43);
        assertThat(JSON.readTree(JSON.writeValueAsString(response.options()))).isEqualTo(JSON.readTree("""
                {"challenge":"%s","timeout":300000,"rpId":"hrva.cc","allowCredentials":[{"type":"public-key","id":"%s","transports":["usb","nfc"]}],"userVerification":"preferred","extensions":{}}
                """.formatted(ceremony.getValue().challenge(), CREDENTIAL_ID)));
        verifyNoInteractions(rateLimiter, loginAttemptService);
    }

    @Test
    void disableOptionsForAnAccountWithoutPasskeysAre400() {
        final var user = signedIn(withAuthenticatorApp());
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of());

        assertThatThrownBy(() -> twoStepSettingsService.disableOptions())
                .isInstanceOf(ApiException.class)
                .hasMessage("This account has no passkeys or security keys.");
        verifyNoInteractions(passkeyCeremonyStore);
    }

    @Test
    void removingTotpWhilePasskeysRemainKeepsTwoStepAndCodes() {
        final var user = signedIn(withAuthenticatorApp());
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(1L);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);

        twoStepSettingsService.removeTotp(new TwoFactorCodeDto("123456"));

        assertThat(user.getTwoFactorEnabled()).isFalse();
        assertThat(user.getTwoFactorSecret()).isNull();
        assertThat(user.getTwoStepEnabled()).isTrue();
        verify(userRepository).save(user);
        verify(loginAttemptService).loginSucceeded(TWO_FACTOR_KEY);
        verifyNoInteractions(recoveryCodes);
    }

    @Test
    void removingTotpWhenItIsTheLastMethodIsRefused() {
        final var user = signedIn(withAuthenticatorApp());
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(0L);

        assertThatThrownBy(() -> twoStepSettingsService.removeTotp(new TwoFactorCodeDto("123456")))
                .isInstanceOf(ApiException.class)
                .hasMessage(LAST_TOTP);

        assertThat(user.getTwoFactorEnabled()).isTrue();
        assertThat(user.getTwoFactorSecret()).isEqualTo("SECRET");
        verifyNoInteractions(twoFactorService, loginAttemptService, recoveryCodes);
        verify(userRepository, never()).save(any());
    }

    @Test
    void removingTotpThatIsNotSetUpIsRefused() {
        signedIn(keysOnly(true));

        assertThatThrownBy(() -> twoStepSettingsService.removeTotp(new TwoFactorCodeDto("123456")))
                .isInstanceOf(ApiException.class)
                .hasMessage("No authenticator app is set up.");
        verifyNoInteractions(twoFactorService, loginAttemptService);
    }

    @Test
    void statusReportsTotpPasskeysAndUnusedCodes() {
        final var keysOnly = signedIn(keysOnly(true));
        when(passkeyCredentialRepository.countByUser(keysOnly)).thenReturn(2L);
        when(recoveryCodes.remaining(keysOnly)).thenReturn(10L);

        assertThat(twoStepSettingsService.status()).isEqualTo(new TwoStepStatusResponse(true, false, 2L, 10L));

        final var off = signedIn(User.builder().id(8L).twoStepEnabled(false).twoFactorEnabled(false).build());
        when(passkeyCredentialRepository.countByUser(off)).thenReturn(0L);
        when(recoveryCodes.remaining(off)).thenReturn(0L);

        assertThat(twoStepSettingsService.status()).isEqualTo(new TwoStepStatusResponse(false, false, 0L, 0L));

        final var withApp = signedIn(withAuthenticatorApp());
        when(passkeyCredentialRepository.countByUser(withApp)).thenReturn(0L);
        when(recoveryCodes.remaining(withApp)).thenReturn(7L);

        assertThat(twoStepSettingsService.status()).isEqualTo(new TwoStepStatusResponse(true, true, 0L, 7L));
    }

    @Test
    void turningOnLocksTheAccountBeforeCountingItsPasskeys() {
        final var user = signedIn(keysOnly(false));
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(1L);
        when(recoveryCodes.issueIfNone(user)).thenReturn(TEN_CODES);

        twoStepSettingsService.enableWithPasskeys();

        final var order = inOrder(accountLock, passkeyCredentialRepository, userRepository, recoveryCodes);
        order.verify(accountLock).lock(user);
        order.verify(passkeyCredentialRepository).countByUser(user);
        order.verify(userRepository).save(user);
        order.verify(recoveryCodes).issueIfNone(user);
    }

    @Test
    void removingTotpLocksTheAccountBeforeCountingItsPasskeys() {
        final var user = signedIn(withAuthenticatorApp());
        when(passkeyCredentialRepository.countByUser(user)).thenReturn(1L);
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);

        twoStepSettingsService.removeTotp(new TwoFactorCodeDto("123456"));

        final var order = inOrder(accountLock, passkeyCredentialRepository, twoFactorService, userRepository);
        order.verify(accountLock).lock(user);
        order.verify(passkeyCredentialRepository).countByUser(user);
        order.verify(twoFactorService).verifyLoginCode(user, "123456");
        order.verify(userRepository).save(user);
    }

    @Test
    void turningOffLocksTheAccountBeforeTheFactorIsChecked() {
        final var user = signedIn(withAuthenticatorApp());
        when(twoFactorService.verifyLoginCode(user, "123456")).thenReturn(true);

        twoStepSettingsService.disable(TwoStepDisableDto.builder().code("123456").build());

        final var order = inOrder(accountLock, loginAttemptService, twoFactorService, userRepository, recoveryCodes);
        order.verify(accountLock).lock(user);
        order.verify(loginAttemptService).isBlocked(TWO_FACTOR_KEY);
        order.verify(twoFactorService).verifyLoginCode(user, "123456");
        order.verify(userRepository).save(user);
        order.verify(recoveryCodes).deleteAll(user);
    }

    @Test
    void theLockedAccountDecidesNotTheCopyFromTheToken() {
        final var fromTheToken = signedIn(keysOnly(false));
        when(accountLock.lock(fromTheToken)).thenReturn(keysOnly(true));
        when(passkeyCredentialRepository.countByUser(any())).thenReturn(1L);

        assertThatThrownBy(() -> twoStepSettingsService.enableWithPasskeys())
                .isInstanceOf(ApiException.class)
                .hasMessage("Two-step verification is already on.");

        final var appFromTheToken = signedIn(withAuthenticatorApp());
        when(accountLock.lock(appFromTheToken)).thenReturn(keysOnly(true));

        assertThatThrownBy(() -> twoStepSettingsService.removeTotp(new TwoFactorCodeDto("123456")))
                .isInstanceOf(ApiException.class)
                .hasMessage("No authenticator app is set up.");

        final var onInTheToken = signedIn(withAuthenticatorApp());
        when(accountLock.lock(onInTheToken)).thenReturn(keysOnly(false));

        assertThatThrownBy(() -> twoStepSettingsService.disable(TwoStepDisableDto.builder().code("123456").build()))
                .isInstanceOf(ApiException.class)
                .hasMessage(OFF);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(recoveryCodes, twoFactorService);
        verify(loginAttemptService, never()).isBlocked(anyString());
    }

    @Test
    void readingTheStatusOrTheTurnOffOptionsTakesNoLock() {
        final var user = signedIn(keysOnly(true));
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of(securityKey()));
        when(passkeyCeremonyStore.save(any())).thenReturn(CEREMONY_ID);

        twoStepSettingsService.status();
        twoStepSettingsService.disableOptions();

        verifyNoInteractions(accountLock);
    }

    @Test
    void withoutASignedInUserNothingHappens() {
        when(userService.getUserFromToken()).thenReturn(null);

        assertThatThrownBy(() -> twoStepSettingsService.status())
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Not authenticated");
        assertThatThrownBy(() -> twoStepSettingsService.disable(TwoStepDisableDto.builder().code("123456").build()))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Not authenticated");
        verifyNoInteractions(twoFactorService, passkeyCredentialRepository, recoveryCodes, loginAttemptService, accountLock);
    }

    private User signedIn(final User user) {
        when(userService.getUserFromToken()).thenReturn(user);

        return user;
    }

    private static User keysOnly(final boolean twoStepEnabled) {
        return User.builder().id(7L).email("user@example.com").twoStepEnabled(twoStepEnabled).twoFactorEnabled(false).build();
    }

    private static User withAuthenticatorApp() {
        return User.builder().id(7L).email("user@example.com").twoStepEnabled(true).twoFactorEnabled(true).twoFactorSecret("SECRET").build();
    }

    private static TwoStepDisableDto withPasskey() {
        return TwoStepDisableDto.builder().ceremonyId(CEREMONY_ID).credential(CREDENTIAL).build();
    }

    private static PasskeyCredential securityKey() {
        return PasskeyCredential.builder().credentialId(CREDENTIAL_ID).transports("usb,nfc").build();
    }

    private static Stream<Named<TwoStepDisableDto>> notExactlyOneFactor() {
        return Stream.of(
                Named.of("neither", new TwoStepDisableDto()),
                Named.of("a blank code", TwoStepDisableDto.builder().code("  ").build()),
                Named.of("both", TwoStepDisableDto.builder().code("123456").ceremonyId(CEREMONY_ID).credential(CREDENTIAL).build()),
                Named.of("a code and a ceremony", TwoStepDisableDto.builder().code("123456").ceremonyId(CEREMONY_ID).build()),
                Named.of("a ceremony without a credential", TwoStepDisableDto.builder().ceremonyId(CEREMONY_ID).build()),
                Named.of("a ceremony with a null credential", TwoStepDisableDto.builder().ceremonyId(CEREMONY_ID).credential(NullNode.getInstance()).build()),
                Named.of("a credential without a ceremony", TwoStepDisableDto.builder().credential(CREDENTIAL).build()));
    }

    private static Stream<Optional<PasskeyCeremony>> ceremoniesThatDontTurnThisAccountOff() {
        return Stream.of(
                Optional.empty(),
                Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.SECOND_STEP, CHALLENGE, 7L)),
                Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.SIGN_IN, CHALLENGE, null)),
                Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.REGISTRATION, CHALLENGE, 7L)),
                Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.TWO_STEP_DISABLE, CHALLENGE, 8L)),
                Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.TWO_STEP_DISABLE, CHALLENGE, null)));
    }

}
