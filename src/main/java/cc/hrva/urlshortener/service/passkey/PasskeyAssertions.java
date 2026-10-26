package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.util.exception.WebAuthnException;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Slf4j
@Component
@RequiredArgsConstructor
public class PasskeyAssertions {

    public static final String NOT_REGISTERED =
            "This passkey isn't registered on hrva.cc. Remove it from your password manager or device, or sign in another way.";
    public static final String COULD_NOT_VERIFY = "That passkey or security key couldn't be verified. Try again.";

    private static final int LOGGED_CREDENTIAL_PREFIX = 8;
    private static final int SIGN_COUNT_END = 37;

    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final UserRepository userRepository;
    private final JpaUserCredentialRepository userCredentialRepository;
    private final PasskeyJson passkeyJson;
    private final PasskeyOptionsFactory passkeyOptionsFactory;
    private final WebAuthnRelyingPartyOperations relyingPartyOperations;
    private final AuthenticatorDataConverter authenticatorDataConverter = new AuthenticatorDataConverter(new ObjectConverter());

    public User verify(final PasskeyCeremony ceremony, final JsonNode credential, final User expectedUser) {
        final var assertion = passkeyJson.readAssertion(credential);
        final var credentialId = assertion.getRawId().toBase64UrlString();
        final var passkey = passkeyCredentialRepository.findByCredentialId(credentialId)
                .orElseThrow(() -> notRegistered(credentialId));
        final var owner = passkey.getUser();
        if (expectedUser != null && !Objects.equals(owner.getId(), expectedUser.getId())) {
            log.info("Passkey of another account at the second step user id={} credential={}…", expectedUser.getId(),
                    prefixOf(credentialId));
            throw couldNotVerify();
        }

        final var authenticatorData = assertion.getResponse().getAuthenticatorData().getBytes();
        checkSignCount(passkey, authenticatorData);

        final var userVerification = ceremony.purpose().userVerification();
        final var options = passkeyOptionsFactory.requestOptions(Bytes.fromBase64(ceremony.challenge()),
                allowCredentialsFor(expectedUser), userVerification);
        final var userEntity = authenticate(options, assertion, passkey);
        checkUserHandle(assertion.getResponse().getUserHandle(), userEntity, userVerification, passkey);

        final var user = userRepository.findByWebauthnUserHandle(userEntity.getId().toBase64UrlString())
                .filter(account -> Objects.equals(account.getId(), owner.getId()))
                .orElseThrow(() -> userHandleRefused(passkey));
        userCredentialRepository.recordBackupState(assertion.getRawId(),
                authenticatorDataConverter.convert(authenticatorData).isFlagBS());

        return user;
    }

    private void checkSignCount(final PasskeyCredential passkey, final byte[] authenticatorData) {
        if (authenticatorData.length < SIGN_COUNT_END) {
            log.info("Passkey assertion refused user id={} credential={}… reason=AuthenticatorDataTooShort",
                    passkey.getUser().getId(), prefixOf(passkey.getCredentialId()));
            throw couldNotVerify();
        }

        final var presented = authenticatorDataConverter.extractSignCount(authenticatorData);
        if (SignCountPolicy.isRegression(passkey.getSignCount(), presented)) {
            log.warn("Passkey sign count did not increase user id={} credential={}… stored={} presented={}",
                    passkey.getUser().getId(), prefixOf(passkey.getCredentialId()), passkey.getSignCount(), presented);
            throw couldNotVerify();
        }
    }

    private List<PublicKeyCredentialDescriptor> allowCredentialsFor(final User expectedUser) {
        if (expectedUser == null) {
            return List.of();
        }

        return passkeyOptionsFactory.descriptors(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(expectedUser));
    }

    private PublicKeyCredentialUserEntity authenticate(
            final PublicKeyCredentialRequestOptions options,
            final PublicKeyCredential<AuthenticatorAssertionResponse> assertion,
            final PasskeyCredential passkey) {
        try {
            return relyingPartyOperations.authenticate(new RelyingPartyAuthenticationRequest(options, assertion));
        } catch (final IllegalStateException exception) {
            log.warn("Passkey assertion refused user id={} credential={}…", passkey.getUser().getId(),
                    prefixOf(passkey.getCredentialId()), exception);
            throw couldNotVerify();
        } catch (final WebAuthnException | IllegalArgumentException exception) {
            log.info("Passkey assertion refused user id={} credential={}… reason={}", passkey.getUser().getId(),
                    prefixOf(passkey.getCredentialId()), exception.getClass().getSimpleName());
            throw couldNotVerify();
        }
    }

    private void checkUserHandle(
            final Bytes userHandle,
            final PublicKeyCredentialUserEntity userEntity,
            final UserVerificationRequirement userVerification,
            final PasskeyCredential passkey) {
        final var matches = userHandle == null
                ? !UserVerificationRequirement.REQUIRED.equals(userVerification)
                : userHandle.equals(userEntity.getId());
        if (!matches) {
            throw userHandleRefused(passkey);
        }
    }

    private PasskeyVerificationException userHandleRefused(final PasskeyCredential passkey) {
        log.warn("Passkey user handle refused user id={} credential={}…", passkey.getUser().getId(),
                prefixOf(passkey.getCredentialId()));

        return couldNotVerify();
    }

    private static PasskeyVerificationException notRegistered(final String credentialId) {
        log.info("Passkey not registered credential={}…", prefixOf(credentialId));

        return new PasskeyVerificationException(NOT_REGISTERED);
    }

    private static PasskeyVerificationException couldNotVerify() {
        return new PasskeyVerificationException(COULD_NOT_VERIFY);
    }

    private static String prefixOf(final String credentialId) {
        return StringUtils.left(credentialId, LOGGED_CREDENTIAL_PREFIX);
    }

}
