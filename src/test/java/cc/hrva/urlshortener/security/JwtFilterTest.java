package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtFilterTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-10-30T19:02:13Z");

    private JwtFilter jwtFilter;

    @Mock
    private TokenProvider tokenProvider;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FilterChain filterChain;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        this.jwtFilter = new JwtFilter(tokenProvider, userRepository, JsonMapper.builder().build());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAuthenticateActiveUser() throws Exception {
        signedInAs("test@example.com", User.builder().id(1L).active(true).build());

        jwtFilter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldNotAuthenticateInactiveUser() throws Exception {
        signedInAs("evil@example.com", User.builder().id(1L).active(false).build());

        jwtFilter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldNotAuthenticateWhenNoToken() throws Exception {
        jwtFilter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void anInvalidTokenLeavesTheRequestSignedOut() throws Exception {
        request.addHeader("Authorization", "Bearer jwt");
        when(tokenProvider.validateToken("jwt")).thenReturn(false);

        jwtFilter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void aTokenIssuedBeforeTheAccountsSessionsEndedIsRefusedWithA401() throws Exception {
        final var user = User.builder().id(1L).active(true).build();
        user.endSessions(ISSUED_AT.plusSeconds(1));
        signedInAs("test@example.com", user);

        jwtFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Your session ended. Sign in again.\"}");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void aTokenIssuedBeforeTheAccountWasCreatedIsRefusedLikeAnEndedSession() throws Exception {
        final var user = User.builder().id(2L).active(true)
                .createDate(java.time.LocalDateTime.ofInstant(ISSUED_AT.plusSeconds(60), java.time.ZoneId.systemDefault())).build();
        signedInAs("reused@example.com", user);

        jwtFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Your session ended. Sign in again.\"}");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void aTokenIssuedBeforeTheAccountWasCreatedIsIgnoredOnAPublicRead() throws Exception {
        final var user = User.builder().id(2L).active(true)
                .createDate(java.time.LocalDateTime.ofInstant(ISSUED_AT.plusSeconds(60), java.time.ZoneId.systemDefault())).build();
        signedInAs("reused@example.com", user);
        request.setRequestURI("/api/changelog");

        jwtFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void aTokenIssuedInTheSecondTheAccountWasCreatedWorks() throws Exception {
        final var user = User.builder().id(2L).active(true)
                .createDate(java.time.LocalDateTime.ofInstant(ISSUED_AT.plusMillis(700), java.time.ZoneId.systemDefault())).build();
        signedInAs("new@example.com", user);

        jwtFilter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void anEndedTokenOnAPublicEndpointIsJustIgnored() throws Exception {
        final var user = User.builder().id(1L).active(true).build();
        user.endSessions(ISSUED_AT.plusSeconds(1));
        signedInAs("test@example.com", user);
        request.setRequestURI("/api/v1/public/settings");

        jwtFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void aTokenIssuedInTheSecondTheSessionsEndedStillWorks() throws Exception {
        final var user = User.builder().id(1L).active(true).build();
        user.endSessions(ISSUED_AT.plusMillis(900));
        signedInAs("test@example.com", user);

        jwtFilter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void aTokenWithoutAnIssueTimeIsRefusedOnceTheAccountsSessionsEnded() throws Exception {
        final var user = User.builder().id(1L).active(true).build();
        user.endSessions(ISSUED_AT);
        signedInAs("test@example.com", user);
        when(tokenProvider.issuedAt("jwt")).thenReturn(Optional.empty());

        jwtFilter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void anAccountWhoseSessionsNeverEndedSkipsTheIssueTime() throws Exception {
        signedInAs("test@example.com", User.builder().id(1L).active(true).build());

        jwtFilter.doFilter(request, response, filterChain);

        verify(tokenProvider, never()).issuedAt(any());
        verify(filterChain).doFilter(request, response);
    }

    private void signedInAs(final String email, final User user) {
        request.addHeader("Authorization", "Bearer jwt");
        final var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        final var authentication = new UsernamePasswordAuthenticationToken(email, "jwt", authorities);
        when(tokenProvider.validateToken("jwt")).thenReturn(true);
        when(tokenProvider.getAuthentication("jwt")).thenReturn(authentication);
        lenient().when(tokenProvider.issuedAt("jwt")).thenReturn(Optional.of(ISSUED_AT));
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
    }
}
