package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.AuthenticatorAttachment;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialPropertiesOutput;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasskeyJsonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String UNREADABLE = "That passkey response couldn't be read. Try again.";

    private final PasskeyJson passkeyJson = new PasskeyJson();

    @Test
    void readsARegistrationFromTheTestAuthenticator() {
        final var authenticator = TestAuthenticator.syncedPasskey();

        final var credential = passkeyJson.readRegistration(authenticator.register(creationOptions()));

        assertThat(credential.getId()).isEqualTo(authenticator.credentialId());
        assertThat(credential.getRawId().toBase64UrlString()).isEqualTo(authenticator.credentialId());
        assertThat(credential.getType()).isEqualTo(PublicKeyCredentialType.PUBLIC_KEY);
        assertThat(credential.getAuthenticatorAttachment()).isEqualTo(AuthenticatorAttachment.PLATFORM);
        assertThat(credential.getResponse().getTransports())
                .containsExactly(AuthenticatorTransport.HYBRID, AuthenticatorTransport.INTERNAL);
        assertThat(credential.getResponse().getAttestationObject()).isNotNull();
        assertThat(credential.getResponse().getClientDataJSON()).isNotNull();
        assertThat(credential.getClientExtensionResults().getOutputs()).singleElement()
                .isInstanceOfSatisfying(CredentialPropertiesOutput.class, output -> assertThat(output.getOutput().isRk()).isTrue());
    }

    @Test
    void readsAnAssertionFromTheTestAuthenticator() {
        final var authenticator = TestAuthenticator.deviceBoundPasskey();
        authenticator.register(creationOptions());

        final var credential = passkeyJson.readAssertion(authenticator.authenticate(requestOptions()));

        assertThat(credential.getRawId().toBase64UrlString()).isEqualTo(authenticator.credentialId());
        assertThat(credential.getResponse().getAuthenticatorData()).isNotNull();
        assertThat(credential.getResponse().getSignature()).isNotNull();
        assertThat(credential.getResponse().getUserHandle()).isNotNull();
    }

    @Test
    void dropsUnknownTransportsBeforeReading() {
        final var registration = registration();
        response(registration).set("transports", JSON.readTree("[\"cable\",\"usb\"]"));

        assertThat(passkeyJson.readRegistration(registration).getResponse().getTransports())
                .containsExactly(AuthenticatorTransport.USB);
    }

    @Test
    void keepsSmartCardNowThatSpringReadsIt() {
        final var registration = registration();
        response(registration).set("transports", JSON.readTree("[\"smart-card\",\"nfc\"]"));

        assertThat(passkeyJson.readRegistration(registration).getResponse().getTransports())
                .containsExactly(AuthenticatorTransport.SMART_CARD, AuthenticatorTransport.NFC);
    }

    @Test
    void acceptsAMissingTransportsList() {
        final var registration = registration();
        response(registration).remove("transports");

        assertThat(passkeyJson.readRegistration(registration).getResponse().getTransports()).isNullOrEmpty();
    }

    @Test
    void acceptsANullAttachment() {
        final var registration = registration();
        registration.putNull("authenticatorAttachment");

        assertThat(passkeyJson.readRegistration(registration).getAuthenticatorAttachment()).isNull();
    }

    @Test
    void ignoresExtraToJsonFields() {
        final var registration = registration();
        registration.put("passwordManagerHint", "bitwarden");
        response(registration)
                .put("publicKey", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE")
                .put("publicKeyAlgorithm", -7)
                .put("authenticatorData", "SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2NdAAAAAA");

        assertThat(passkeyJson.readRegistration(registration).getRawId().toBase64UrlString())
                .isEqualTo(registration.path("rawId").asString());
    }

    @Test
    void ignoresUnknownExtensionOutputs() {
        final var registration = registration();
        registration.set("clientExtensionResults", JSON.readTree("{\"largeBlob\":{},\"credProps\":{\"rk\":false}}"));

        assertThat(passkeyJson.readRegistration(registration).getClientExtensionResults().getOutputs()).singleElement()
                .isInstanceOfSatisfying(CredentialPropertiesOutput.class, output -> assertThat(output.getOutput().isRk()).isFalse());
    }

    @Test
    void acceptsPaddedBase64url() {
        final var registration = registration();
        final var credentialId = registration.path("rawId").asString();
        registration.put("id", padded(credentialId)).put("rawId", padded(credentialId));
        response(registration)
                .put("clientDataJSON", padded(response(registration).path("clientDataJSON").asString()))
                .put("attestationObject", padded(response(registration).path("attestationObject").asString()));

        assertThat(passkeyJson.readRegistration(registration).getRawId()).isEqualTo(Bytes.fromBase64(credentialId));
    }

    @Test
    void aBadBase64IdIs400() {
        final var registration = registration();
        registration.put("rawId", "not*base64!");

        assertThatThrownBy(() -> passkeyJson.readRegistration(registration))
                .isInstanceOf(ApiException.class)
                .hasMessage(UNREADABLE);
    }

    @Test
    void aMissingResponseIs400() {
        final var registration = registration();
        registration.remove("response");

        assertThatThrownBy(() -> passkeyJson.readRegistration(registration))
                .isInstanceOf(ApiException.class)
                .hasMessage(UNREADABLE);
    }

    @Test
    void aMissingRawIdIs400() {
        final var registration = registration();
        registration.remove("rawId");

        assertThatThrownBy(() -> passkeyJson.readRegistration(registration))
                .isInstanceOf(ApiException.class)
                .hasMessage(UNREADABLE);
    }

    @Test
    void aRegistrationWithoutItsAttestationObjectIs400() {
        final var registration = registration();
        response(registration).remove("attestationObject");

        assertThatThrownBy(() -> passkeyJson.readRegistration(registration))
                .isInstanceOf(ApiException.class)
                .hasMessage(UNREADABLE);
    }

    @Test
    void anAssertionWithoutItsSignatureIs400() {
        final var authenticator = TestAuthenticator.securityKey();
        authenticator.register(creationOptions());
        final var assertion = authenticator.authenticate(requestOptions());
        response(assertion).remove("signature");

        assertThatThrownBy(() -> passkeyJson.readAssertion(assertion))
                .isInstanceOf(ApiException.class)
                .hasMessage(UNREADABLE);
    }

    @Test
    void somethingThatIsNoObjectIs400() {
        assertThatThrownBy(() -> passkeyJson.readRegistration(JSON.readTree("\"credential\"")))
                .isInstanceOf(ApiException.class)
                .hasMessage(UNREADABLE);
        assertThatThrownBy(() -> passkeyJson.readAssertion(null))
                .isInstanceOf(ApiException.class)
                .hasMessage(UNREADABLE);
    }

    private static ObjectNode registration() {
        return TestAuthenticator.syncedPasskey().register(creationOptions());
    }

    private static ObjectNode response(final ObjectNode credential) {
        return (ObjectNode) credential.get("response");
    }

    private static JsonNode creationOptions() {
        return JSON.readTree("""
                {"rp":{"name":"hrva.cc","id":"localhost"},"user":{"name":"user@localhost","id":"%s","displayName":"user@localhost"},"challenge":"%s"}
                """.formatted(Bytes.random().toBase64UrlString(), Bytes.random().toBase64UrlString()));
    }

    private static JsonNode requestOptions() {
        return JSON.readTree("""
                {"challenge":"%s","rpId":"localhost","allowCredentials":[],"userVerification":"preferred"}
                """.formatted(Bytes.random().toBase64UrlString()));
    }

    private static String padded(final String base64Url) {
        return base64Url + "=".repeat((4 - base64Url.length() % 4) % 4);
    }

}
