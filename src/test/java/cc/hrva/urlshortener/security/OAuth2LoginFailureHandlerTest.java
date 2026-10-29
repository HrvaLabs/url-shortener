package cc.hrva.urlshortener.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
    @ExtendWith(OutputCaptureExtension.class)
    void theLoggedErrorCodeCannotForgeALogLine(final CapturedOutput output) throws Exception {
        final var forged = "access_denied\n2026-10-29 INFO forged line " + "x".repeat(100);

        handler.onAuthenticationFailure(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new OAuth2AuthenticationException(new OAuth2Error(forged)));

        final var expected = ("access_denied_2026_10_29_INFO_forged_line_" + "x".repeat(100)).substring(0, 64);
        assertThat(output).contains("OAuth2 login failed reason=" + expected + System.lineSeparator())
                .doesNotContain("forged line");
    }

    @Test
    void anyOtherSignInFailureGoesTheSameWay() throws Exception {
        final var response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, new InternalAuthenticationServiceException("boom"));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://fe/auth/callback?error=provider_error");
    }

}
