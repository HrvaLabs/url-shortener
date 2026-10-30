package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.impl.DefaultUserDetailsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordSignInWithoutAPasswordTest {

    private static final String WITH_PASSWORD = "has-password@example.com";
    private static final String WITHOUT_PASSWORD = "no-password@example.com";
    private static final String GOOGLE_ONLY = "google-only@example.com";
    private static final String PASSWORD = "correct horse battery";

    private final BCryptPasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder());
    private final List<AbstractAuthenticationFailureEvent> failures = new ArrayList<>();
    private AuthenticationManager authenticationManager;

    @BeforeEach
    void setUp() {
        final var userRepository = mock(UserRepository.class);
        when(userRepository.findByEmail(WITH_PASSWORD)).thenReturn(Optional.of(account(WITH_PASSWORD, new BCryptPasswordEncoder().encode(PASSWORD))));
        when(userRepository.findByEmail(WITHOUT_PASSWORD)).thenReturn(Optional.of(account(WITHOUT_PASSWORD, StoredPasswords.none())));
        when(userRepository.findByEmail(GOOGLE_ONLY)).thenReturn(Optional.of(account(GOOGLE_ONLY, "6c1b2a3e-0b8e-4c1e-9d0e-1f2a3b4c5d6e")));
        final var provider = new DaoAuthenticationProvider(new DefaultUserDetailsService(userRepository, passwordEncoder));
        provider.setPasswordEncoder(passwordEncoder);
        final var providerManager = new ProviderManager(provider);
        providerManager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(event -> {
            if (event instanceof AbstractAuthenticationFailureEvent failure) {
                failures.add(failure);
            }
        }));
        authenticationManager = providerManager;
    }

    @Test
    void anAccountWithoutAPasswordIsAnsweredExactlyLikeAWrongPassword() {
        final var wrongPassword = refusal(WITH_PASSWORD, "not the password");
        final var noPassword = refusal(WITHOUT_PASSWORD, PASSWORD);

        assertThat(noPassword).isInstanceOf(BadCredentialsException.class).hasMessage(wrongPassword.getMessage());
        assertThat(wrongPassword).isInstanceOf(BadCredentialsException.class);
        final var handler = new GlobalExceptionHandler();
        assertThat(handler.handleBadCredentials((BadCredentialsException) noPassword))
                .isEqualTo(handler.handleBadCredentials((BadCredentialsException) wrongPassword));
        assertThat(handler.handleBadCredentials((BadCredentialsException) noPassword).getStatusCode().value()).isEqualTo(401);
        assertThat(failures).hasSize(2).allMatch(AuthenticationFailureBadCredentialsEvent.class::isInstance);
    }

    @Test
    void anAccountWithoutAPasswordStillRunsAFullPasswordCheck() {
        refusal(WITHOUT_PASSWORD, PASSWORD);

        verify(passwordEncoder).encode(argThat(raw -> !"userNotFoundPassword".equals(raw)));
        verify(passwordEncoder).matches(anyString(), argThat(StoredPasswords::isSet));
    }

    @Test
    void anOAuthAccountsRandomPasswordIsAnsweredLikeAWrongPasswordAfterAFullCheck() {
        assertThat(refusal(GOOGLE_ONLY, "6c1b2a3e-0b8e-4c1e-9d0e-1f2a3b4c5d6e")).isInstanceOf(BadCredentialsException.class);

        verify(passwordEncoder).matches(anyString(), argThat(StoredPasswords::isSet));
    }

    @Test
    void theRightPasswordStillSignsIn() {
        assertThat(authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(WITH_PASSWORD, PASSWORD))
                .isAuthenticated()).isTrue();
    }

    private AuthenticationException refusal(final String email, final String password) {
        final var refused = new AuthenticationException[1];
        assertThatThrownBy(() -> authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password)))
                .isInstanceOfSatisfying(AuthenticationException.class, exception -> refused[0] = exception);

        return refused[0];
    }

    private static User account(final String email, final String password) {
        return User.builder()
                .id((long) email.hashCode())
                .email(email)
                .password(password)
                .active(true)
                .authorities(List.of(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build();
    }

}
