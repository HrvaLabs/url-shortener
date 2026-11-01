package cc.hrva.urlshortener.security;

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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverlongPasswordSignInTest {

    private static final String EMAIL = "long@example.com";
    private static final String FIRST_72_BYTES = "x".repeat(72);

    private final List<AbstractAuthenticationFailureEvent> failures = new ArrayList<>();
    private AuthenticationManager authenticationManager;

    @BeforeEach
    void setUp() {
        final var passwordEncoder = new BcryptPasswords(new BCryptPasswordEncoder(4));
        final var userRepository = mock(UserRepository.class);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(User.builder()
                .id(1L)
                .email(EMAIL)
                .password(new BCryptPasswordEncoder(4).encode(FIRST_72_BYTES))
                .active(true)
                .authorities(List.of(Authorities.builder().id(1L).name("ROLE_USER").build()))
                .build()));
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
    void aPasswordOverSeventyTwoBytesIsAnsweredLikeAWrongPasswordAndCounted() {
        assertThatThrownBy(() -> signIn(FIRST_72_BYTES + "y"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Bad credentials");
        assertThatThrownBy(() -> signIn("wrong"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Bad credentials");

        assertThat(failures).hasSize(2).allMatch(AuthenticationFailureBadCredentialsEvent.class::isInstance);
    }

    @Test
    void seventyTwoBytesStillSignIn() {
        assertThat(signIn(FIRST_72_BYTES).isAuthenticated()).isTrue();
    }

    private org.springframework.security.core.Authentication signIn(final String password) {
        return authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(EMAIL, password));
    }

}
