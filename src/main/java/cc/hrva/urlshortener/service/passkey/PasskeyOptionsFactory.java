package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientInput;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientInputs;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PasskeyOptionsFactory {

    public static final String RELYING_PARTY_NAME = "hrva.cc";

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final AppProperties appProperties;

    public PublicKeyCredentialCreationOptions creationOptions(
            final Bytes challenge,
            final PublicKeyCredentialUserEntity user,
            final List<PublicKeyCredentialDescriptor> excludeCredentials) {
        return PublicKeyCredentialCreationOptions.builder()
                .rp(relyingParty())
                .user(user)
                .challenge(challenge)
                .pubKeyCredParams(PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.EdDSA,
                        PublicKeyCredentialParameters.RS256)
                .timeout(TIMEOUT)
                .excludeCredentials(excludeCredentials)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.PREFERRED)
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build())
                .attestation(AttestationConveyancePreference.NONE)
                .extensions(new ImmutableAuthenticationExtensionsClientInputs(ImmutableAuthenticationExtensionsClientInput.credProps))
                .build();
    }

    public PublicKeyCredentialRequestOptions requestOptions(
            final Bytes challenge,
            final List<PublicKeyCredentialDescriptor> allowCredentials,
            final UserVerificationRequirement userVerification) {
        return PublicKeyCredentialRequestOptions.builder()
                .challenge(challenge)
                .timeout(TIMEOUT)
                .rpId(appProperties.getPasskeys().getRpId())
                .allowCredentials(allowCredentials)
                .userVerification(userVerification)
                .build();
    }

    public PublicKeyCredentialUserEntity userEntity(final User user) {
        return userEntityOf(user);
    }

    public List<PublicKeyCredentialDescriptor> descriptors(final List<PasskeyCredential> passkeys) {
        return passkeys.stream()
                .map(passkey -> PublicKeyCredentialDescriptor.builder()
                        .id(Bytes.fromBase64(passkey.getCredentialId()))
                        .transports(transportsOf(passkey))
                        .build())
                .toList();
    }

    static PublicKeyCredentialUserEntity userEntityOf(final User user) {
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .name(user.getEmail())
                .id(Bytes.fromBase64(user.getWebauthnUserHandle()))
                .displayName(user.getEmail())
                .build();
    }

    private PublicKeyCredentialRpEntity relyingParty() {
        return PublicKeyCredentialRpEntity.builder()
                .id(appProperties.getPasskeys().getRpId())
                .name(RELYING_PARTY_NAME)
                .build();
    }

    static Set<AuthenticatorTransport> transportsOf(final PasskeyCredential passkey) {
        return passkey.transportList().stream()
                .map(AuthenticatorTransport::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

}
