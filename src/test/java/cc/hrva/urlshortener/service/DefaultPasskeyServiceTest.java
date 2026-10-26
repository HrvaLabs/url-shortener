package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.PasskeyDeleteDto;
import cc.hrva.urlshortener.dto.PasskeyRegistrationDto;
import cc.hrva.urlshortener.dto.PasskeyRegistrationOptionsDto;
import cc.hrva.urlshortener.dto.PasskeyRenameDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.PasskeyNotFoundException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.service.impl.DefaultPasskeyService;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonies;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremony;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyStore;
import cc.hrva.urlshortener.service.passkey.PasskeyJson;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import cc.hrva.urlshortener.service.passkey.TestAuthenticator;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.verifier.exception.BadOriginException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultPasskeyServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CEREMONY_ID = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n";
    private static final String CHALLENGE = Bytes.random().toBase64UrlString();
    private static final String COULD_NOT_VERIFY = "That passkey or security key couldn't be verified. Try again.";
    private static final String ALREADY_ADDED = "This passkey or security key is already added.";
    private static final String EXPIRED = "This request expired. Try again.";
    private static final String RACED_CREDENTIAL_ID = "c2FtZS1jcmVkZW50aWFs";

    @Mock
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @Mock
    private PasskeyCeremonyStore passkeyCeremonyStore;

    @Mock
    private WebAuthnRelyingPartyOperations relyingPartyOperations;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private Reauthentication reauthentication;

    private final Map<Long, String> storedHandles = new HashMap<>();

    private PasskeyService passkeyService;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.getPasskeys().setRpId("localhost");
        appProperties.getPasskeys().setAllowedOrigins(List.of("http://localhost:3000"));
        final var passkeyJson = new PasskeyJson();
        final var passkeyOptionsFactory = new PasskeyOptionsFactory(appProperties);
        passkeyService = new DefaultPasskeyService(userService, userRepository, passkeyCredentialRepository,
                passkeyCeremonyStore, new PasskeyCeremonies(passkeyCeremonyStore, passkeyOptionsFactory, passkeyJson, rateLimiter),
                passkeyJson, passkeyOptionsFactory, relyingPartyOperations, reauthentication);
    }

    @Test
    void theUserHandleIsRandomAndNeverTheEmailOrId() {
        final var first = user(4_815_162_342L, "first.person@example.com");
        final var second = user(4_815_162_343L, "second.person@example.com");
        when(userService.getUserFromToken()).thenReturn(first, second);
        when(passkeyCeremonyStore.save(any())).thenReturn(CEREMONY_ID);
        handlesAreStored();

        passkeyService.registrationOptions(new PasskeyRegistrationOptionsDto("user123"));
        passkeyService.registrationOptions(new PasskeyRegistrationOptionsDto("user123"));

        for (final var user : List.of(first, second)) {
            final var id = String.valueOf(user.getId());
            assertThat(user.getWebauthnUserHandle())
                    .isEqualTo(storedHandles.get(user.getId()))
                    .hasSize(43)
                    .matches("[A-Za-z0-9_-]{43}")
                    .doesNotContain(user.getEmail())
                    .doesNotContain(id)
                    .doesNotContain(base64Url(user.getEmail()))
                    .doesNotContain(base64Url(id));
        }
        assertThat(first.getWebauthnUserHandle()).isNotEqualTo(second.getWebauthnUserHandle());
        verify(userRepository, never()).save(any());
    }

    @Test
    void aHandleSetByARacingRequestIsUsed() {
        final var user = user(7L, "user@localhost");
        final var stored = Bytes.random().toBase64UrlString();
        when(userService.getUserFromToken()).thenReturn(user);
        when(userRepository.setWebauthnUserHandleIfMissing(eq(7L), anyString())).thenReturn(0);
        when(userRepository.findWebauthnUserHandleById(7L)).thenReturn(Optional.of(stored));
        when(passkeyCeremonyStore.save(any())).thenReturn(CEREMONY_ID);

        final var response = passkeyService.registrationOptions(new PasskeyRegistrationOptionsDto("user123"));

        final var generated = ArgumentCaptor.forClass(String.class);
        verify(userRepository).setWebauthnUserHandleIfMissing(eq(7L), generated.capture());
        assertThat(generated.getValue()).hasSize(43).isNotEqualTo(stored);
        assertThat(response.options().path("user").path("id").asString()).isEqualTo(stored);
        assertThat(user.getWebauthnUserHandle()).isEqualTo(stored);
        verify(userRepository, never()).save(any());
    }

    @Test
    void anAccountDeletedMeanwhileGetsNoOptions() {
        final var user = user(7L, "user@localhost");
        when(userService.getUserFromToken()).thenReturn(user);
        when(userRepository.setWebauthnUserHandleIfMissing(eq(7L), anyString())).thenReturn(0);
        when(userRepository.findWebauthnUserHandleById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passkeyService.registrationOptions(new PasskeyRegistrationOptionsDto("user123")))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Not authenticated");

        verifyNoInteractions(passkeyCeremonyStore);
    }

    @Test
    void anExistingHandleIsKept() {
        final var handle = Bytes.random().toBase64UrlString();
        final var user = user(7L, "user@localhost");
        user.setWebauthnUserHandle(handle);
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCeremonyStore.save(any())).thenReturn(CEREMONY_ID);

        final var response = passkeyService.registrationOptions(new PasskeyRegistrationOptionsDto("user123"));

        assertThat(user.getWebauthnUserHandle()).isEqualTo(handle);
        assertThat(response.options().path("user").path("id").asString()).isEqualTo(handle);
        verify(userRepository, never()).setWebauthnUserHandleIfMissing(any(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void registrationOptionsSaveARegistrationCeremonyForTheUser() {
        final var user = user(7L, "user@localhost");
        final var keyId = Bytes.random().toBase64UrlString();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user))
                .thenReturn(List.of(PasskeyCredential.builder().credentialId(keyId).transports("usb").build()));
        when(passkeyCeremonyStore.save(any())).thenReturn(CEREMONY_ID);
        handlesAreStored();

        final var response = passkeyService.registrationOptions(new PasskeyRegistrationOptionsDto("user123"));

        final var ceremony = ArgumentCaptor.forClass(PasskeyCeremony.class);
        verify(passkeyCeremonyStore).save(ceremony.capture());
        assertThat(ceremony.getValue().purpose()).isEqualTo(PasskeyCeremonyPurpose.REGISTRATION);
        assertThat(ceremony.getValue().userId()).isEqualTo(7L);
        assertThat(ceremony.getValue().challenge()).hasSize(43).isEqualTo(response.options().path("challenge").asString());
        assertThat(response.ceremonyId()).isEqualTo(CEREMONY_ID);
        assertThat(response.options().path("excludeCredentials")).containsExactly(JSON.readTree("""
                {"type":"public-key","id":"%s","transports":["usb"]}""".formatted(keyId)));
        verify(reauthentication).confirm(user, "user123");
    }

    @Test
    void optionsAreRefusedBeforeAnyCeremonyWhenThePasswordIsWrong() {
        final var user = user(7L, "user@localhost");
        when(userService.getUserFromToken()).thenReturn(user);
        doThrow(new NoAuthorizationException("Current password is incorrect")).when(reauthentication).confirm(user, "wrong");

        assertThatThrownBy(() -> passkeyService.registrationOptions(new PasskeyRegistrationOptionsDto("wrong")))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Current password is incorrect");

        verifyNoInteractions(passkeyCeremonyStore);
        verify(userRepository, never()).setWebauthnUserHandleIfMissing(any(), any());
        verify(userRepository, never()).save(any());
        assertThat(user.getWebauthnUserHandle()).isNull();
    }

    @Test
    void registerRebuildsTheOptionsFromTheCeremonyAndTrimsTheLabel() {
        final var user = userWithHandle();
        final var registration = TestAuthenticator.syncedPasskey().register(creationOptions(user));
        final var credentialId = registration.path("rawId").asString();
        final var stored = PasskeyCredential.builder().id(3L).user(user).credentialId(credentialId).label("MacBook")
                .backupEligible(true).backupState(true).transports("hybrid,internal").createdAt(LocalDateTime.now()).build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(registrationCeremony(user));
        when(passkeyCredentialRepository.findByCredentialId(credentialId)).thenReturn(Optional.empty(), Optional.of(stored));

        final var response = passkeyService.register(registrationDto(registration, "  MacBook  "));

        final var request = ArgumentCaptor.forClass(RelyingPartyRegistrationRequest.class);
        verify(relyingPartyOperations).registerCredential(request.capture());
        final var options = request.getValue().getCreationOptions();
        assertThat(options.getChallenge().toBase64UrlString()).isEqualTo(CHALLENGE);
        assertThat(options.getUser().getId().toBase64UrlString()).isEqualTo(user.getWebauthnUserHandle());
        assertThat(options.getRp().getId()).isEqualTo("localhost");
        assertThat(request.getValue().getPublicKey().getLabel()).isEqualTo("MacBook");
        assertThat(request.getValue().getPublicKey().getCredential().getRawId().toBase64UrlString()).isEqualTo(credentialId);
        assertThat(stored.getDiscoverable()).isTrue();
        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.discoverable()).isTrue();
        verify(passkeyCredentialRepository).save(stored);
        verifyNoInteractions(reauthentication);
    }

    @Test
    void aMissingCeremonyIsExpired() {
        final var user = userWithHandle();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passkeyService.register(registrationDto(registration(user), "MacBook")))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);

        verifyNoInteractions(relyingPartyOperations);
    }

    @Test
    void aCeremonyForAnotherPurposeIsExpired() {
        final var user = userWithHandle();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID))
                .thenReturn(Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.SECOND_STEP, CHALLENGE, user.getId())));

        assertThatThrownBy(() -> passkeyService.register(registrationDto(registration(user), "MacBook")))
                .isInstanceOf(SignInExpiredException.class)
                .hasMessage(EXPIRED);

        verifyNoInteractions(relyingPartyOperations);
    }

    @Test
    void aDuplicateIsRefusedBeforeTheLibraryRuns() {
        final var user = userWithHandle();
        final var registration = registration(user);
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(registrationCeremony(user));
        when(passkeyCredentialRepository.findByCredentialId(registration.path("rawId").asString()))
                .thenReturn(Optional.of(PasskeyCredential.builder().id(3L).build()));

        assertThatThrownBy(() -> passkeyService.register(registrationDto(registration, "MacBook")))
                .isInstanceOf(ApiException.class)
                .hasMessage(ALREADY_ADDED);

        verifyNoInteractions(relyingPartyOperations);
    }

    @Test
    void libraryRefusalsAreTheVerifyTextAndNeverTheLibrarysOwn() {
        final var user = userWithHandle();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenAnswer(invocation -> registrationCeremony(user));
        final var refusals = new ArrayList<RuntimeException>(List.of(
                new BadOriginException("The origin http://evil.example is not allowed"),
                new DataConversionException("Failed to read attestationObject"),
                new IllegalStateException("No user for handle"),
                new IllegalArgumentException("attestationObject cannot be null")));
        when(relyingPartyOperations.registerCredential(any())).thenAnswer(invocation -> {
            throw refusals.removeFirst();
        });

        for (var attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> passkeyService.register(registrationDto(registration(user), "MacBook")))
                    .isInstanceOf(PasskeyVerificationException.class)
                    .hasMessage(COULD_NOT_VERIFY)
                    .hasNoCause();
        }
        verify(relyingPartyOperations, times(4)).registerCredential(any());
    }

    @ParameterizedTest
    @MethodSource("credentialIdConstraintRaces")
    @ExtendWith(OutputCaptureExtension.class)
    void aCredentialIdConstraintRaceIsTheDuplicateTextAndItsDetailIsNeverLogged(
            final DataIntegrityViolationException race, final CapturedOutput output) {
        final var user = userWithHandle();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(registrationCeremony(user));
        when(relyingPartyOperations.registerCredential(any())).thenThrow(race);

        assertThatThrownBy(() -> passkeyService.register(registrationDto(registration(user), "MacBook")))
                .isInstanceOf(ApiException.class)
                .hasMessage(ALREADY_ADDED);
        assertThat(output)
                .contains("Passkey already added user id=7")
                .doesNotContain(RACED_CREDENTIAL_ID)
                .doesNotContainIgnoringCase("uq_passkey_credential_credential_id");
    }

    @Test
    void anotherConstraintViolationIsRethrown() {
        final var user = userWithHandle();
        final var race = new DataIntegrityViolationException("could not execute statement", new SQLException(
                "ERROR: insert or update on table \"passkey_credential\" violates foreign key constraint \"fk_passkey_credential_user\"\n"
                        + "  Detail: Key (user_id)=(7) is not present in table \"user_account\".", "23503"));
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(registrationCeremony(user));
        when(relyingPartyOperations.registerCredential(any())).thenThrow(race);

        assertThatThrownBy(() -> passkeyService.register(registrationDto(registration(user), "MacBook")))
                .isSameAs(race);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anUnexpectedStateIsLoggedAtWarnWithItsStackTrace(final CapturedOutput output) {
        final var user = userWithHandle();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenReturn(registrationCeremony(user));
        when(relyingPartyOperations.registerCredential(any()))
                .thenThrow(new IllegalStateException("No account has this passkey user handle"));

        assertThatThrownBy(() -> passkeyService.register(registrationDto(registration(user), "MacBook")))
                .isInstanceOf(PasskeyVerificationException.class)
                .hasMessage(COULD_NOT_VERIFY)
                .hasNoCause();
        assertThat(output.getOut().lines().filter(line -> line.contains("Passkey refused user id=7")))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("WARN").doesNotContain("reason="));
        assertThat(output).contains("java.lang.IllegalStateException: No account has this passkey user handle")
                .contains("\tat ");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void otherRefusalsAreLoggedByClassNameOnly(final CapturedOutput output) {
        final var user = userWithHandle();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCeremonyStore.consume(CEREMONY_ID)).thenAnswer(invocation -> registrationCeremony(user));
        when(relyingPartyOperations.registerCredential(any()))
                .thenThrow(new BadOriginException("The origin http://evil.example is not allowed"))
                .thenThrow(new IllegalArgumentException("Credential with id a2V5 already exists"));

        for (var attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> passkeyService.register(registrationDto(registration(user), "MacBook")))
                    .isInstanceOf(PasskeyVerificationException.class)
                    .hasMessage(COULD_NOT_VERIFY);
        }
        assertThat(output)
                .contains("reason=BadOriginException")
                .contains("reason=IllegalArgumentException")
                .doesNotContain("evil.example")
                .doesNotContain("already exists");
    }

    @Test
    void renameTrimsTheLabel() {
        final var user = user(7L, "user@localhost");
        final var passkey = PasskeyCredential.builder().id(5L).user(user).credentialId("a2V5").label("YubiKey")
                .transports("usb,nfc").createdAt(LocalDateTime.now()).build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCredentialRepository.findByIdAndUser(5L, user)).thenReturn(Optional.of(passkey));

        final var response = passkeyService.rename(5L, new PasskeyRenameDto("  Work YubiKey  "));

        assertThat(response.label()).isEqualTo("Work YubiKey");
        assertThat(response.transports()).containsExactly("usb", "nfc");
        assertThat(passkey.getLabel()).isEqualTo("Work YubiKey");
        verify(passkeyCredentialRepository).save(passkey);
        verifyNoInteractions(reauthentication);
    }

    @Test
    void anotherUsersPasskeyIsNotFound() {
        final var user = user(7L, "user@localhost");
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCredentialRepository.findByIdAndUser(5L, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passkeyService.rename(5L, new PasskeyRenameDto("Mine now")))
                .isInstanceOf(PasskeyNotFoundException.class)
                .hasMessage("Passkey not found");
        assertThatThrownBy(() -> passkeyService.delete(5L, new PasskeyDeleteDto("user123")))
                .isInstanceOf(PasskeyNotFoundException.class)
                .hasMessage("Passkey not found");

        verify(passkeyCredentialRepository, never()).save(any());
        verify(passkeyCredentialRepository, never()).delete(any());
    }

    @Test
    void deleteNeedsThePassword() {
        final var user = user(7L, "user@localhost");
        when(userService.getUserFromToken()).thenReturn(user);
        doThrow(new ApiException("Enter your current password.")).when(reauthentication).confirm(user, null);

        assertThatThrownBy(() -> passkeyService.delete(5L, new PasskeyDeleteDto(null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("Enter your current password.");

        verify(passkeyCredentialRepository, never()).findByIdAndUser(any(), any());
        verify(passkeyCredentialRepository, never()).delete(any());
    }

    @Test
    void deleteRemovesTheCallersPasskey() {
        final var user = user(7L, "user@localhost");
        final var passkey = PasskeyCredential.builder().id(5L).user(user).credentialId("a2V5").build();
        when(userService.getUserFromToken()).thenReturn(user);
        when(passkeyCredentialRepository.findByIdAndUser(5L, user)).thenReturn(Optional.of(passkey));

        passkeyService.delete(5L, new PasskeyDeleteDto("user123"));

        verify(reauthentication).confirm(user, "user123");
        verify(passkeyCredentialRepository).delete(passkey);
    }

    @Test
    void withoutASignedInUserNothingHappens() {
        when(userService.getUserFromToken()).thenReturn(null);

        assertThatThrownBy(passkeyService::list)
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Not authenticated");
        assertThatThrownBy(() -> passkeyService.registrationOptions(new PasskeyRegistrationOptionsDto("user123")))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Not authenticated");

        verifyNoInteractions(reauthentication, passkeyCeremonyStore, passkeyCredentialRepository);
    }

    private static Stream<Named<DataIntegrityViolationException>> credentialIdConstraintRaces() {
        return Stream.of(
                Named.of("H2", new DataIntegrityViolationException("could not execute statement", new SQLIntegrityConstraintViolationException(
                        "Unique index or primary key violation: \"PUBLIC.UQ_PASSKEY_CREDENTIAL_CREDENTIAL_ID_INDEX_E ON PUBLIC.PASSKEY_CREDENTIAL(CREDENTIAL_ID NULLS FIRST) VALUES ( /* 1 */ '"
                                + RACED_CREDENTIAL_ID + "' )\"", "23505"))),
                Named.of("Postgres", new DataIntegrityViolationException("could not execute statement", new SQLException(
                        "ERROR: duplicate key value violates unique constraint \"uq_passkey_credential_credential_id\"\n"
                                + "  Detail: Key (credential_id)=(" + RACED_CREDENTIAL_ID + ") already exists.", "23505"))));
    }

    private void handlesAreStored() {
        when(userRepository.setWebauthnUserHandleIfMissing(anyLong(), anyString())).thenAnswer(invocation ->
                storedHandles.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null ? 1 : 0);
        when(userRepository.findWebauthnUserHandleById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(storedHandles.get(invocation.<Long>getArgument(0))));
    }

    private static User user(final Long id, final String email) {
        return User.builder().id(id).email(email).authProvider("local").build();
    }

    private static User userWithHandle() {
        final var user = user(7L, "user@localhost");
        user.setWebauthnUserHandle(Bytes.random().toBase64UrlString());

        return user;
    }

    private static Optional<PasskeyCeremony> registrationCeremony(final User user) {
        return Optional.of(new PasskeyCeremony(PasskeyCeremonyPurpose.REGISTRATION, CHALLENGE, user.getId()));
    }

    private static JsonNode registration(final User user) {
        return TestAuthenticator.syncedPasskey().register(creationOptions(user));
    }

    private static PasskeyRegistrationDto registrationDto(final JsonNode credential, final String label) {
        return PasskeyRegistrationDto.builder().ceremonyId(CEREMONY_ID).credential(credential).label(label).build();
    }

    private static JsonNode creationOptions(final User user) {
        return JSON.readTree("""
                {"rp":{"name":"hrva.cc","id":"localhost"},"user":{"name":"%s","id":"%s","displayName":"%s"},"challenge":"%s"}
                """.formatted(user.getEmail(), user.getWebauthnUserHandle(), user.getEmail(), CHALLENGE));
    }

    private static String base64Url(final String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

}
