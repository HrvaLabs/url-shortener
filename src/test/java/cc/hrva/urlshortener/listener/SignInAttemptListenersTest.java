package cc.hrva.urlshortener.listener;

import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SignInAttemptListenersTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);

    @Test
    void aWrongPasswordCountsForTheNetworkAndTheAccount() {
        when(clientIpResolver.getClientIp(request)).thenReturn("203.0.113.7");

        new AuthenticationFailureListener(request, clientIpResolver, loginAttemptService).onApplicationEvent(
                new AuthenticationFailureBadCredentialsEvent(UsernamePasswordAuthenticationToken.unauthenticated("ana@hrva.cc", "wrong"),
                        new BadCredentialsException("Bad credentials")));

        verify(loginAttemptService).signInFailed("203.0.113.7", "ana@hrva.cc");
        verifyNoMoreInteractions(loginAttemptService);
    }

    @Test
    void aRightPasswordClearsOnlyThatAccountsCounter() {
        new AuthenticationSuccessEventListener(loginAttemptService).onApplicationEvent(
                new AuthenticationSuccessEvent(UsernamePasswordAuthenticationToken.unauthenticated("ana@hrva.cc", "right")));

        verify(loginAttemptService).signInSucceeded("ana@hrva.cc");
        verifyNoMoreInteractions(loginAttemptService);
    }


    @Test
    void otherAuthenticationsNeverTouchTheSignInCounters() {
        final var bearer = new org.springframework.security.authentication.TestingAuthenticationToken("mcp-client", "token");

        new AuthenticationSuccessEventListener(loginAttemptService).onApplicationEvent(new AuthenticationSuccessEvent(bearer));
        new AuthenticationFailureListener(request, clientIpResolver, loginAttemptService).onApplicationEvent(
                new AuthenticationFailureBadCredentialsEvent(bearer, new BadCredentialsException("Bad credentials")));

        org.mockito.Mockito.verifyNoInteractions(loginAttemptService);
    }

}
