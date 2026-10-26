package cc.hrva.urlshortener.security;

import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecentSignIn {

    static final Duration WINDOW = Duration.ofMinutes(15);

    private final TokenProvider tokenProvider;
    private final Clock clock;

    public boolean isRecent(final Authentication authentication) {
        if (authentication == null || !(authentication.getCredentials() instanceof String token)) {
            return false;
        }

        final var oldestRecent = clock.instant().minus(WINDOW);

        return tokenProvider.issuedAt(token)
                .map(issuedAt -> !issuedAt.isBefore(oldestRecent))
                .orElse(false);
    }

}
