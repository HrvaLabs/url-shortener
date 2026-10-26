package cc.hrva.urlshortener.service.passkey;

import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class TestAuthenticator {

    public static final String RP_ID = "localhost";
    public static final String ORIGIN = "http://localhost:3000";

    private static final ObjectConverter OBJECT_CONVERTER = new ObjectConverter();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CREDENTIAL_ID_BYTES = 32;

    private final KeyPair keyPair;
    private final byte[] credentialId;
    private final AtomicLong nextCounter;
    private final AtomicReference<byte[]> userHandle;
    private final boolean resident;
    private final boolean userPresent;
    private final boolean userVerified;
    private final boolean backedUp;
    private final boolean countsUp;
    private final List<String> transports;
    private final String attachment;
    private final String origin;

    private TestAuthenticator(
            final KeyPair keyPair,
            final byte[] credentialId,
            final AtomicLong nextCounter,
            final AtomicReference<byte[]> userHandle,
            final boolean resident,
            final boolean userPresent,
            final boolean userVerified,
            final boolean backedUp,
            final boolean countsUp,
            final List<String> transports,
            final String attachment,
            final String origin) {
        this.keyPair = keyPair;
        this.credentialId = credentialId;
        this.nextCounter = nextCounter;
        this.userHandle = userHandle;
        this.resident = resident;
        this.userPresent = userPresent;
        this.userVerified = userVerified;
        this.backedUp = backedUp;
        this.countsUp = countsUp;
        this.transports = transports;
        this.attachment = attachment;
        this.origin = origin;
    }

    public static TestAuthenticator syncedPasskey() {
        return create(0, true, true, true, false, List.of("hybrid", "internal"), "platform");
    }

    public static TestAuthenticator deviceBoundPasskey() {
        return create(1, true, true, false, true, List.of("internal"), "platform");
    }

    public static TestAuthenticator securityKey() {
        return create(1, false, false, false, true, List.of("usb", "nfc"), "cross-platform");
    }

    public TestAuthenticator withoutUserPresence() {
        return new TestAuthenticator(keyPair, credentialId, nextCounter, userHandle, resident, false, userVerified, backedUp,
                countsUp, transports, attachment, origin);
    }

    public TestAuthenticator withoutUserVerification() {
        return new TestAuthenticator(keyPair, credentialId, nextCounter, userHandle, resident, userPresent, false, backedUp,
                countsUp, transports, attachment, origin);
    }

    public TestAuthenticator withOrigin(final String otherOrigin) {
        return new TestAuthenticator(keyPair, credentialId, nextCounter, userHandle, resident, userPresent, userVerified,
                backedUp, countsUp, transports, attachment, otherOrigin);
    }

    public TestAuthenticator withNextCounter(final long counter) {
        nextCounter.set(counter);

        return this;
    }

    public TestAuthenticator withTransports(final String... values) {
        return new TestAuthenticator(keyPair, credentialId, nextCounter, userHandle, resident, userPresent, userVerified,
                backedUp, countsUp, List.of(values), attachment, origin);
    }

    public TestAuthenticator withoutTransports() {
        return new TestAuthenticator(keyPair, credentialId, nextCounter, userHandle, resident, userPresent, userVerified,
                backedUp, countsUp, null, attachment, origin);
    }

    public String credentialId() {
        return BASE64_URL.encodeToString(credentialId);
    }

    public ObjectNode register(final JsonNode creationOptions) {
        userHandle.set(BASE64_URL_DECODER.decode(creationOptions.path("user").path("id").asString()));
        final var clientDataJson = clientDataJson("webauthn.create", creationOptions.path("challenge").asString());
        final var attestedCredentialData = new AttestedCredentialData(AAGUID.ZERO, credentialId,
                EC2COSEKey.create(keyPair, COSEAlgorithmIdentifier.ES256));
        final var authenticatorData = new AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(
                rpIdHash(creationOptions.path("rp").path("id").asString()), (byte) (flags() | AuthenticatorData.BIT_AT),
                signCount(), attestedCredentialData);
        final var attestationObject = OBJECT_CONVERTER.getCborConverter()
                .writeValueAsBytes(new AttestationObject(authenticatorData, new NoneAttestationStatement()));

        final var response = JSON.createObjectNode()
                .put("clientDataJSON", BASE64_URL.encodeToString(clientDataJson))
                .put("attestationObject", BASE64_URL.encodeToString(attestationObject));
        if (transports != null) {
            final var reportedTransports = response.putArray("transports");
            transports.forEach(reportedTransports::add);
        }

        final var credential = credential(response);
        credential.putObject("clientExtensionResults").putObject("credProps").put("rk", resident);

        return credential;
    }

    public ObjectNode authenticate(final JsonNode requestOptions) {
        final var clientDataJson = clientDataJson("webauthn.get", requestOptions.path("challenge").asString());
        final var authenticatorData = new AuthenticatorDataConverter(OBJECT_CONVERTER).convert(
                new AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>(
                        rpIdHash(requestOptions.path("rpId").asString()), flags(), signCount()));

        final var response = JSON.createObjectNode()
                .put("clientDataJSON", BASE64_URL.encodeToString(clientDataJson))
                .put("authenticatorData", BASE64_URL.encodeToString(authenticatorData))
                .put("signature", BASE64_URL.encodeToString(sign(authenticatorData, sha256(clientDataJson))));
        if (resident) {
            response.put("userHandle", BASE64_URL.encodeToString(userHandle.get()));
        }

        final var credential = credential(response);
        credential.putObject("clientExtensionResults");

        return credential;
    }

    private ObjectNode credential(final ObjectNode response) {
        final var credential = JSON.createObjectNode()
                .put("id", credentialId())
                .put("rawId", credentialId())
                .put("type", "public-key")
                .put("authenticatorAttachment", attachment);
        credential.set("response", response);

        return credential;
    }

    private byte[] clientDataJson(final String type, final String challenge) {
        final var clientData = JSON.createObjectNode()
                .put("type", type)
                .put("challenge", challenge)
                .put("origin", origin)
                .put("crossOrigin", false);

        return JSON.writeValueAsString(clientData).getBytes(StandardCharsets.UTF_8);
    }

    private byte flags() {
        byte flags = 0;
        if (userPresent) {
            flags |= AuthenticatorData.BIT_UP;
        }
        if (userVerified) {
            flags |= AuthenticatorData.BIT_UV;
        }
        if (backedUp) {
            flags |= AuthenticatorData.BIT_BE | AuthenticatorData.BIT_BS;
        }

        return flags;
    }

    private long signCount() {
        final var count = nextCounter.get();
        if (countsUp) {
            nextCounter.incrementAndGet();
        }

        return count;
    }

    private byte[] sign(final byte[] authenticatorData, final byte[] clientDataHash) {
        try {
            final var signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(ByteBuffer.allocate(authenticatorData.length + clientDataHash.length)
                    .put(authenticatorData)
                    .put(clientDataHash)
                    .array());

            return signature.sign();
        } catch (final GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static TestAuthenticator create(
            final long firstCounter,
            final boolean resident,
            final boolean userVerified,
            final boolean backedUp,
            final boolean countsUp,
            final List<String> transports,
            final String attachment) {
        final var credentialId = new byte[CREDENTIAL_ID_BYTES];
        RANDOM.nextBytes(credentialId);

        return new TestAuthenticator(newKeyPair(), credentialId, new AtomicLong(firstCounter), new AtomicReference<>(),
                resident, true, userVerified, backedUp, countsUp, transports, attachment, ORIGIN);
    }

    private static KeyPair newKeyPair() {
        try {
            final var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));

            return generator.generateKeyPair();
        } catch (final GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] rpIdHash(final String rpId) {
        return sha256(rpId.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(final byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (final GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
