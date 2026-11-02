package cc.hrva.urlshortener.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

@Slf4j
public class OAuthChallengeRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String NO_CHALLENGE = "no_challenge";

    private final OAuth2AuthorizationRequestResolver delegate;

    public OAuthChallengeRequestResolver(final ClientRegistrationRepository clientRegistrationRepository) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(final HttpServletRequest request) {
        return withChallenge(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(final HttpServletRequest request, final String clientRegistrationId) {
        return withChallenge(request, delegate.resolve(request, clientRegistrationId));
    }

    private static OAuth2AuthorizationRequest withChallenge(final HttpServletRequest request, final OAuth2AuthorizationRequest authorizationRequest) {
        if (authorizationRequest == null) {
            return null;
        }
        final var challenge = request.getParameter(OAuthChallenges.PARAMETER);
        if (!OAuthChallenges.wellFormed(challenge)) {
            log.info("OAuth2 sign-in start refused, it has no well-formed challenge");
            throw new OAuth2AuthenticationException(new OAuth2Error(NO_CHALLENGE));
        }
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .attributes(attributes -> attributes.put(OAuthChallenges.ATTRIBUTE, challenge))
                .build();
    }

}
