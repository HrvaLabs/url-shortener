package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PasskeyOptionsFactoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PasskeyJson passkeyJson = new PasskeyJson();

    private PasskeyOptionsFactory passkeyOptionsFactory;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.getPasskeys().setRpId("localhost");
        appProperties.getPasskeys().setAllowedOrigins(List.of("http://localhost:3000"));
        passkeyOptionsFactory = new PasskeyOptionsFactory(appProperties);
    }

    @Test
    void creationOptionsUseTheCompatibilityValues() {
        final var handle = Bytes.random().toBase64UrlString();
        final var keyId = Bytes.random().toBase64UrlString();
        final var user = User.builder().id(7L).email("user@localhost").webauthnUserHandle(handle).build();
        final var securityKey = PasskeyCredential.builder().credentialId(keyId).transports("usb,nfc").build();

        final var options = passkeyJson.write(passkeyOptionsFactory.creationOptions(Bytes.random(),
                passkeyOptionsFactory.userEntity(user), passkeyOptionsFactory.descriptors(List.of(securityKey))));

        assertThat(options.path("rp")).isEqualTo(JSON.readTree("{\"name\":\"hrva.cc\",\"id\":\"localhost\"}"));
        assertThat(options.path("user").path("name").asString()).isEqualTo("user@localhost");
        assertThat(options.path("user").path("displayName").asString()).isEqualTo("user@localhost");
        assertThat(options.path("user").path("id").asString()).isEqualTo(handle);
        assertThat(options.path("challenge").asString()).hasSize(43);
        assertThat(options.path("pubKeyCredParams").values()).extracting(parameter -> parameter.path("alg").asInt())
                .containsExactly(-7, -8, -257);
        assertThat(options.path("pubKeyCredParams").values()).extracting(parameter -> parameter.path("type").asString())
                .containsOnly("public-key");
        assertThat(options.path("timeout").asLong()).isEqualTo(300_000L);
        assertThat(options.path("authenticatorSelection"))
                .isEqualTo(JSON.readTree("{\"residentKey\":\"preferred\",\"userVerification\":\"preferred\"}"));
        assertThat(options.path("authenticatorSelection").has("authenticatorAttachment")).isFalse();
        assertThat(options.path("attestation").asString()).isEqualTo("none");
        assertThat(options.path("extensions")).isEqualTo(JSON.readTree("{\"credProps\":true}"));
        assertThat(options.path("excludeCredentials")).containsExactly(JSON.readTree("""
                {"type":"public-key","id":"%s","transports":["usb","nfc"]}""".formatted(keyId)));
    }

    @Test
    void aPasskeyWithoutTransportsIsDescribedWithAnEmptyList() {
        final var keyId = Bytes.random().toBase64UrlString();
        final var passkey = PasskeyCredential.builder().credentialId(keyId).build();

        final var options = passkeyJson.write(passkeyOptionsFactory.requestOptions(Bytes.random(),
                passkeyOptionsFactory.descriptors(List.of(passkey)), UserVerificationRequirement.PREFERRED));

        assertThat(options.path("allowCredentials")).containsExactly(JSON.readTree("""
                {"type":"public-key","id":"%s","transports":[]}""".formatted(keyId)));
    }

    @Test
    void requestOptionsForPasswordlessSignIn() {
        final var options = passkeyJson.write(passkeyOptionsFactory.requestOptions(Bytes.random(), List.of(),
                PasskeyCeremonyPurpose.SIGN_IN.userVerification()));

        assertThat(options.path("rpId").asString()).isEqualTo("localhost");
        assertThat(options.path("allowCredentials")).isEqualTo(JSON.createArrayNode());
        assertThat(options.path("userVerification").asString()).isEqualTo("required");
        assertThat(options.path("timeout").asLong()).isEqualTo(300_000L);
        assertThat(options.path("challenge").asString()).hasSize(43);
    }

    @Test
    void onlyPasswordlessSignInRequiresUserVerification() {
        assertThat(PasskeyCeremonyPurpose.SIGN_IN.userVerification()).isEqualTo(UserVerificationRequirement.REQUIRED);
        assertThat(List.of(PasskeyCeremonyPurpose.REGISTRATION, PasskeyCeremonyPurpose.SECOND_STEP, PasskeyCeremonyPurpose.TWO_STEP_DISABLE))
                .extracting(PasskeyCeremonyPurpose::userVerification)
                .containsOnly(UserVerificationRequirement.PREFERRED);
    }

    @Test
    void theUserEntityIsTheEmailAndTheHandle() {
        final var handle = Bytes.random().toBase64UrlString();
        final var user = User.builder().id(7L).email("user@localhost").webauthnUserHandle(handle).build();

        final var userEntity = passkeyOptionsFactory.userEntity(user);

        assertThat(userEntity.getName()).isEqualTo("user@localhost");
        assertThat(userEntity.getDisplayName()).isEqualTo("user@localhost");
        assertThat(userEntity.getId()).isEqualTo(Bytes.fromBase64(handle));
    }

}
