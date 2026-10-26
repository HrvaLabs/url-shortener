package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.passkey.JpaPublicKeyCredentialUserEntityRepository;
import cc.hrva.urlshortener.service.passkey.JpaUserCredentialRepository;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.verifier.CoreMaliciousCounterValueHandler;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

@Configuration
public class PasskeyConfiguration {

    private static final CoreMaliciousCounterValueHandler COUNTER_CHECKED_BY_THE_PASSKEY_SERVICE = authenticationObject -> {
    };

    @Bean
    public WebAuthnRelyingPartyOperations relyingPartyOperations(
            final JpaPublicKeyCredentialUserEntityRepository userEntities,
            final JpaUserCredentialRepository credentials,
            final AppProperties appProperties) {
        final var passkeys = appProperties.getPasskeys();
        final var relyingParty = PublicKeyCredentialRpEntity.builder()
                .id(passkeys.getRpId())
                .name(PasskeyOptionsFactory.RELYING_PARTY_NAME)
                .build();
        final var webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager();
        webAuthnManager.getAuthenticationDataVerifier().setMaliciousCounterValueHandler(COUNTER_CHECKED_BY_THE_PASSKEY_SERVICE);

        final var relyingPartyOperations = new Webauthn4JRelyingPartyOperations(userEntities, credentials, relyingParty,
                Set.copyOf(passkeys.getAllowedOrigins()));
        relyingPartyOperations.setWebAuthnManager(webAuthnManager);

        return relyingPartyOperations;
    }

}
