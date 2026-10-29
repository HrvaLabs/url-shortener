package cc.hrva.urlshortener.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

@Slf4j
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

    private final String frontendUrl;

    public OAuth2LoginFailureHandler(final String frontendUrl) {
        this.frontendUrl = frontendUrl;
    }

    @Override
    public void onAuthenticationFailure(final HttpServletRequest request, final HttpServletResponse response,
                                        final AuthenticationException exception) throws IOException {
        final var reason = exception instanceof OAuth2AuthenticationException oAuth2Exception
                ? oAuth2Exception.getError().getErrorCode()
                : exception.getClass().getSimpleName();
        log.warn("OAuth2 login failed reason={}", reason);
        response.sendRedirect(frontendUrl + "/auth/callback?error=provider_error");
    }

}
