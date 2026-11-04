package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentSessionTest {

    private static final String SECRET = "d2hhdGV2ZXIgeW91IGRvLCBkbyBub3QgdXNlIHRoaXMgaW4gcHJvZHVjdGlvbiEgVGhpcyBpcyBhIHNlY3JldCBrZXkhISE=";
    private static final String EMAIL = "ana@hrva.cc";

    private final UserRepository userRepository = mock(UserRepository.class);
    private TokenProvider tokenProvider;
    private CurrentSession currentSession;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setJwtBase64Secret(SECRET);
        appProperties.setJwtTokenValiditySeconds(36_000L);
        tokenProvider = new TokenProvider(appProperties);
        tokenProvider.init();
        currentSession = new CurrentSession(tokenProvider, userRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theDeviceGetsATokenIssuedNowWithTheSameSignInTimeAndALongerSessionKeptAndTheRolesItHasNow() {
        final var signedIn = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        final var expires = Instant.now().plus(28, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        signedInWith(tokenIssued(signedIn, expires, "ROLE_USER,ROLE_ADMIN"));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true, "ROLE_USER")));

        final var renewed = currentSession.renewedToken().orElseThrow();

        assertThat(renewed).startsWith("Bearer ");
        final var token = tokenProvider.read(renewed.substring("Bearer ".length())).orElseThrow();
        assertThat(token.issuedAt()).isAfterOrEqualTo(Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(1));
        assertThat(token.signedInAt()).isEqualTo(signedIn);
        assertThat(token.expiresAt()).isEqualTo(expires);
        assertThat(token.authentication().getName()).isEqualTo(EMAIL);
        assertThat(token.authentication().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        assertThat(currentSession.keepThisDeviceSignedIn().getFirst("Authorization")).startsWith("Bearer ");
    }

    @Test
    void nothingIsRenewedWithoutASignedInToken() {
        assertThat(currentSession.renewedToken()).isEmpty();
        assertThat(currentSession.keepThisDeviceSignedIn().containsHeader("Authorization")).isFalse();

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(EMAIL, null, List.of()));
        assertThat(currentSession.renewedToken()).isEmpty();
    }

    @Test
    void aTokenWithoutAnIssueTimeIsNotRenewed() {
        signedInWith(Jwts.builder()
                .subject(EMAIL)
                .claim("auth", "ROLE_USER")
                .expiration(Date.from(Instant.now().plus(20, ChronoUnit.DAYS)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)), Jwts.SIG.HS512)
                .compact());
        org.mockito.Mockito.lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(true, "ROLE_USER")));

        assertThat(currentSession.renewedToken()).isEmpty();
        assertThat(currentSession.keepThisDeviceSignedIn().containsHeader("Authorization")).isFalse();
    }

    @Test
    void aDeactivatedAccountGetsNoNewToken() {
        signedInWith(tokenIssued(Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "ROLE_USER"));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user(false, "ROLE_USER")));

        assertThat(currentSession.renewedToken()).isEmpty();
    }

    private void signedInWith(final String jwt) {
        SecurityContextHolder.getContext().setAuthentication(tokenProvider.getAuthentication(jwt));
    }

    private static String tokenIssued(final Instant issuedAt, final Instant expiresAt, final String roles) {
        return Jwts.builder()
                .subject(EMAIL)
                .claim("auth", roles)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)), Jwts.SIG.HS512)
                .compact();
    }

    private static User user(final boolean active, final String role) {
        final var authority = new Authorities();
        authority.setName(role);
        return User.builder().id(7L).email(EMAIL).active(active).authorities(List.of(authority)).build();
    }

}
