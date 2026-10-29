package cc.hrva.urlshortener.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2LoginFailureHandlerTest {

    private final OAuth2LoginFailureHandler handler = new OAuth2LoginFailureHandler("http://fe");

    @Test
    void aProviderErrorSendsTheBrowserBackToTheSiteWithoutTheProvidersText() throws Exception {
        final var response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied", "<script>the user said no</script>", null)));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://fe/auth/callback?error=provider_error");
    }

    @Test
    void anyOtherSignInFailureGoesTheSameWay() throws Exception {
        final var response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, new InternalAuthenticationServiceException("boom"));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://fe/auth/callback?error=provider_error");
    }

}
