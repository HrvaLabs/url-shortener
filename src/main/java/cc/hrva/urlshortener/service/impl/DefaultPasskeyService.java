package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.PasskeyDeleteDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeyRegistrationDto;
import cc.hrva.urlshortener.dto.PasskeyRegistrationOptionsDto;
import cc.hrva.urlshortener.dto.PasskeyRenameDto;
import cc.hrva.urlshortener.dto.PasskeyResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.PasskeyNotFoundException;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.service.PasskeyService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremony;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyStore;
import cc.hrva.urlshortener.service.passkey.PasskeyJson;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import com.webauthn4j.util.exception.WebAuthnException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.web.webauthn.api.AuthenticationExtensionsClientOutputs;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialPropertiesOutput;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultPasskeyService implements PasskeyService {

    private static final String DEFAULT_LABEL = "Passkey";
    private static final int MAX_LABEL_LENGTH = 64;
    private static final int LOGGED_CREDENTIAL_PREFIX = 8;
    private static final String EXPIRED = "This request expired. Try again.";
    private static final String ALREADY_ADDED = "This passkey or security key is already added.";
    private static final String COULD_NOT_VERIFY = "That passkey or security key couldn't be verified. Try again.";
    private static final String LABEL_TOO_LONG = "A label can have at most 64 characters.";
    private static final String NOT_FOUND = "Passkey not found";
    private static final String NOT_AUTHENTICATED = "Not authenticated";
    private static final String CREDENTIAL_ID_CONSTRAINT = "uq_passkey_credential_credential_id";

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final PasskeyCeremonyStore passkeyCeremonyStore;
    private final PasskeyJson passkeyJson;
    private final PasskeyOptionsFactory passkeyOptionsFactory;
    private final WebAuthnRelyingPartyOperations relyingPartyOperations;
    private final Reauthentication reauthentication;

    @Override
    @Transactional
    public PasskeyOptionsResponse registrationOptions(final PasskeyRegistrationOptionsDto dto) {
        final var user = currentUser();
        reauthentication.confirm(user, dto.getPassword());
        ensureUserHandle(user);

        final var challenge = Bytes.random();
        final var options = passkeyOptionsFactory.creationOptions(challenge, passkeyOptionsFactory.userEntity(user),
                passkeyOptionsFactory.descriptors(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)));
        final var ceremonyId = passkeyCeremonyStore.save(
                new PasskeyCeremony(PasskeyCeremonyPurpose.REGISTRATION, challenge.toBase64UrlString(), user.getId()));

        return new PasskeyOptionsResponse(ceremonyId, passkeyJson.write(options));
    }

    @Override
    @Transactional
    public PasskeyResponse register(final PasskeyRegistrationDto dto) {
        final var user = currentUser();
        final var label = labelOf(dto.getLabel());
        final var ceremony = registrationCeremonyOf(dto.getCeremonyId(), user);
        final var credential = passkeyJson.readRegistration(dto.getCredential());
        final var credentialId = credential.getRawId().toBase64UrlString();
        if (passkeyCredentialRepository.findByCredentialId(credentialId).isPresent()) {
            throw new ApiException(ALREADY_ADDED);
        }

        final var options = passkeyOptionsFactory.creationOptions(Bytes.fromBase64(ceremony.challenge()),
                passkeyOptionsFactory.userEntity(user), List.of());
        try {
            relyingPartyOperations.registerCredential(
                    new ImmutableRelyingPartyRegistrationRequest(options, new RelyingPartyPublicKey(credential, label)));
        } catch (final DataIntegrityViolationException exception) {
            if (!namesTheCredentialIdConstraint(exception)) {
                throw exception;
            }
            log.info("Passkey already added user id={} credential={}…", user.getId(), prefixOf(credentialId));
            throw new ApiException(ALREADY_ADDED);
        } catch (final IllegalStateException exception) {
            log.warn("Passkey refused user id={} credential={}…", user.getId(), prefixOf(credentialId), exception);
            throw new PasskeyVerificationException(COULD_NOT_VERIFY);
        } catch (final WebAuthnException | IllegalArgumentException exception) {
            log.info("Passkey refused user id={} credential={}… reason={}", user.getId(), prefixOf(credentialId),
                    exception.getClass().getSimpleName());
            throw new PasskeyVerificationException(COULD_NOT_VERIFY);
        }

        final var passkey = passkeyCredentialRepository.findByCredentialId(credentialId).orElseThrow();
        passkey.setDiscoverable(discoverableOf(credential));
        passkeyCredentialRepository.save(passkey);
        log.info("Passkey added user id={} credential={}…", user.getId(), prefixOf(credentialId));

        return PasskeyResponse.from(passkey);
    }

    @Override
    public List<PasskeyResponse> list() {
        return passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(currentUser()).stream()
                .map(PasskeyResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public PasskeyResponse rename(final Long id, final PasskeyRenameDto dto) {
        final var user = currentUser();
        final var label = labelOf(dto.getLabel());
        final var passkey = passkeyOf(user, id);
        passkey.setLabel(label);
        passkeyCredentialRepository.save(passkey);
        log.info("Passkey renamed user id={} passkey id={}", user.getId(), passkey.getId());

        return PasskeyResponse.from(passkey);
    }

    @Override
    @Transactional
    public void delete(final Long id, final PasskeyDeleteDto dto) {
        final var user = currentUser();
        reauthentication.confirm(user, dto.getPassword());
        final var passkey = passkeyOf(user, id);
        passkeyCredentialRepository.delete(passkey);
        log.info("Passkey removed user id={} credential={}…", user.getId(), prefixOf(passkey.getCredentialId()));
    }

    private void ensureUserHandle(final User user) {
        if (user.getWebauthnUserHandle() != null) {
            return;
        }

        if (userRepository.setWebauthnUserHandleIfMissing(user.getId(), Bytes.random().toBase64UrlString()) > 0) {
            log.info("Passkey user handle created user id={}", user.getId());
        }
        user.setWebauthnUserHandle(userRepository.findWebauthnUserHandleById(user.getId())
                .orElseThrow(() -> new NoAuthorizationException(NOT_AUTHENTICATED)));
    }

    private PasskeyCeremony registrationCeremonyOf(final String ceremonyId, final User user) {
        return passkeyCeremonyStore.consume(ceremonyId)
                .filter(ceremony -> ceremony.purpose() == PasskeyCeremonyPurpose.REGISTRATION)
                .filter(ceremony -> Objects.equals(ceremony.userId(), user.getId()))
                .filter(ceremony -> user.getWebauthnUserHandle() != null)
                .orElseThrow(() -> new SignInExpiredException(EXPIRED));
    }

    private PasskeyCredential passkeyOf(final User user, final Long id) {
        return passkeyCredentialRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new PasskeyNotFoundException(NOT_FOUND));
    }

    private User currentUser() {
        final var user = userService.getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException(NOT_AUTHENTICATED);
        }

        return user;
    }

    private static String labelOf(final String requested) {
        final var label = StringUtils.trimToEmpty(requested);
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new ApiException(LABEL_TOO_LONG);
        }

        return label.isEmpty() ? DEFAULT_LABEL : label;
    }

    private static Boolean discoverableOf(final PublicKeyCredential<AuthenticatorAttestationResponse> credential) {
        return Optional.ofNullable(credential.getClientExtensionResults())
                .map(AuthenticationExtensionsClientOutputs::getOutputs)
                .orElseGet(List::of)
                .stream()
                .filter(CredentialPropertiesOutput.class::isInstance)
                .map(output -> ((CredentialPropertiesOutput) output).getOutput().isRk())
                .findFirst()
                .orElse(null);
    }

    private static boolean namesTheCredentialIdConstraint(final DataIntegrityViolationException exception) {
        return StringUtils.containsIgnoreCase(exception.getMostSpecificCause().getMessage(), CREDENTIAL_ID_CONSTRAINT);
    }

    private static String prefixOf(final String credentialId) {
        return StringUtils.left(credentialId, LOGGED_CREDENTIAL_PREFIX);
    }

}
