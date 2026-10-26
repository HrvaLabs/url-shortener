package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.anon.RateLimiterUnavailableException;
import cc.hrva.urlshortener.model.User;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PasskeyCeremonies {

    private static final int OPTIONS_PER_NETWORK = 30;
    private static final Duration OPTIONS_WINDOW = Duration.ofMinutes(10);
    private static final String TOO_MANY_OPTIONS = "Too many passkey sign-in attempts from this network. Try again in a few minutes.";

    private final PasskeyCeremonyStore passkeyCeremonyStore;
    private final PasskeyOptionsFactory passkeyOptionsFactory;
    private final PasskeyJson passkeyJson;
    private final RateLimiter rateLimiter;

    public void checkOptionsLimit(final String name, final String clientIp) {
        final boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(RateLimiter.clientKey(name, clientIp), OPTIONS_PER_NETWORK, OPTIONS_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            throw new SignInUnavailableException(PasskeyCeremonyStore.UNAVAILABLE, exception);
        }

        if (!allowed) {
            throw new RateLimitExceededException(TOO_MANY_OPTIONS);
        }
    }

    public PasskeyOptionsResponse startRequest(
            final PasskeyCeremonyPurpose purpose,
            final List<PublicKeyCredentialDescriptor> allowCredentials,
            final Long userId) {
        final var challenge = Bytes.random();
        final var options = passkeyOptionsFactory.requestOptions(challenge, allowCredentials, purpose.userVerification());

        return started(new PasskeyCeremony(purpose, challenge.toBase64UrlString(), userId), options);
    }

    public PasskeyOptionsResponse startRegistration(final User user, final List<PublicKeyCredentialDescriptor> excludeCredentials) {
        final var challenge = Bytes.random();
        final var options = passkeyOptionsFactory.creationOptions(challenge, passkeyOptionsFactory.userEntity(user), excludeCredentials);

        return started(new PasskeyCeremony(PasskeyCeremonyPurpose.REGISTRATION, challenge.toBase64UrlString(), user.getId()), options);
    }

    private PasskeyOptionsResponse started(final PasskeyCeremony ceremony, final Object options) {
        final var ceremonyId = passkeyCeremonyStore.save(ceremony);

        return new PasskeyOptionsResponse(ceremonyId, passkeyJson.write(options));
    }

}
