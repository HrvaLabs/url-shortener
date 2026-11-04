package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.repository.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentSession {

    public static final String TOKEN_HEADER = JwtFilter.AUTHORIZATION_HEADER;
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenProvider tokenProvider;
    private final UserRepository userRepository;

    public Optional<String> renewedToken() {
        final var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getCredentials() instanceof String jwt)) {
            return Optional.empty();
        }

        return tokenProvider.read(jwt)
                .filter(current -> current.issuedAt() != null)
                .flatMap(current -> userRepository.findByEmail(authentication.getName())
                        .filter(user -> !Boolean.FALSE.equals(user.getActive()))
                        .flatMap(user -> tokenProvider.renewToken(TokenProvider.authenticationOf(user), current))
                        .map(token -> BEARER_PREFIX + token));
    }

    public HttpHeaders keepThisDeviceSignedIn() {
        final var headers = new HttpHeaders();
        renewedToken().ifPresent(token -> headers.add(TOKEN_HEADER, token));

        return headers;
    }

}
