package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.exception.ApiException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class PasskeyJson {

    public static final String UNREADABLE = "That passkey response couldn't be read. Try again.";

    private static final String RAW_ID = "rawId";
    private static final String RESPONSE = "response";
    private static final String TRANSPORTS = "transports";
    private static final Set<String> READABLE_TRANSPORTS = Arrays.stream(AuthenticatorTransport.values())
            .map(AuthenticatorTransport::getValue)
            .collect(Collectors.toUnmodifiableSet());

    private final JsonMapper jsonMapper = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();

    public JsonNode write(final Object value) {
        return jsonMapper.valueToTree(value);
    }

    public PublicKeyCredential<AuthenticatorAttestationResponse> readRegistration(final JsonNode credential) {
        final var registration = read(credential, AuthenticatorAttestationResponse.class);
        final var response = registration.getResponse();
        if (response.getClientDataJSON() == null || response.getAttestationObject() == null) {
            throw new ApiException(UNREADABLE);
        }

        return registration;
    }

    public PublicKeyCredential<AuthenticatorAssertionResponse> readAssertion(final JsonNode credential) {
        return read(credential, AuthenticatorAssertionResponse.class);
    }

    private <R extends AuthenticatorResponse> PublicKeyCredential<R> read(final JsonNode credential, final Class<R> responseType) {
        if (credential == null || !credential.isObject() || !credential.hasNonNull(RAW_ID) || !credential.path(RESPONSE).isObject()) {
            throw new ApiException(UNREADABLE);
        }

        try {
            final var type = jsonMapper.getTypeFactory().constructParametricType(PublicKeyCredential.class, responseType);
            final PublicKeyCredential<R> read = jsonMapper.readerFor(type).readValue(withReadableTransports((ObjectNode) credential));
            if (read == null || read.getRawId() == null || read.getResponse() == null) {
                throw new ApiException(UNREADABLE);
            }

            return read;
        } catch (final JacksonException | IllegalArgumentException exception) {
            throw new ApiException(UNREADABLE);
        }
    }

    private static ObjectNode withReadableTransports(final ObjectNode credential) {
        final var copy = credential.deepCopy();
        final var response = (ObjectNode) copy.get(RESPONSE);
        final var transports = response.get(TRANSPORTS);
        if (transports == null || transports.isNull()) {
            return copy;
        }
        if (!transports.isArray()) {
            response.remove(TRANSPORTS);
            return copy;
        }

        final var readable = response.putArray(TRANSPORTS);
        transports.values().stream()
                .filter(JsonNode::isString)
                .map(JsonNode::asString)
                .filter(READABLE_TRANSPORTS::contains)
                .forEach(readable::add);

        return copy;
    }

}
