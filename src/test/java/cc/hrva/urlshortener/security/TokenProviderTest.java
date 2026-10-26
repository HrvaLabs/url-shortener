package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenProviderTest {

    private static final String SECRET = "d2hhdGV2ZXIgeW91IGRvLCBkbyBub3QgdXNlIHRoaXMgaW4gcHJvZHVjdGlvbiEgVGhpcyBpcyBhIHNlY3JldCBrZXkhISE=";

    private TokenProvider tokenProvider;

    @Mock
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        when(appProperties.getJwtBase64Secret()).thenReturn(SECRET);
        when(appProperties.getJwtTokenValiditySeconds()).thenReturn(86400L);
        this.tokenProvider = new TokenProvider(appProperties);
        this.tokenProvider.init();
    }

    @Test
    void shouldCreateAndValidateToken() {
        final var authentication = new UsernamePasswordAuthenticationToken(
                "test@example.com",
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );

        final var token = tokenProvider.createToken(authentication);

        assertThat(token).isNotNull();
        assertThat(tokenProvider.validateToken(token)).isTrue();
    }

    @Test
    void shouldGetAuthenticationFromToken() {
        final var authentication = new UsernamePasswordAuthenticationToken(
                "test@example.com",
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );
        final var token = tokenProvider.createToken(authentication);

        final var result = tokenProvider.getAuthentication(token);

        assertThat(result.getName()).isEqualTo("test@example.com");
        assertThat(result.getAuthorities()).hasSize(1);
        assertThat(result.getAuthorities().iterator().next().getAuthority()).isEqualTo("ROLE_USER");
    }

    @Test
    void shouldInvalidateMalformedToken() {
        assertThat(tokenProvider.validateToken("invalid-token")).isFalse();
    }

    @Test
    void shouldInvalidateEmptyToken() {
        assertThat(tokenProvider.validateToken("")).isFalse();
    }

    @Test
    void newTokensCarryTheirIssueTime() {
        final var authentication = new UsernamePasswordAuthenticationToken(
                "test@example.com",
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );
        final var now = Instant.now();

        final var token = tokenProvider.createToken(authentication, 60);

        assertThat(tokenProvider.issuedAt(token))
                .hasValueSatisfying(issuedAt -> assertThat(issuedAt).isCloseTo(now, within(2, ChronoUnit.SECONDS)));
    }

    @Test
    void aTokenWithoutIatHasNoIssueTime() {
        final var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        final var token = Jwts.builder()
                .subject("test@example.com")
                .claim("auth", "ROLE_USER")
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key, Jwts.SIG.HS512)
                .compact();

        assertThat(tokenProvider.issuedAt(token)).isEmpty();
        assertThat(tokenProvider.validateToken(token)).isTrue();
    }

    @Test
    void aTokenSignedWithAnotherKeyHasNoIssueTime() {
        final var otherKey = Jwts.SIG.HS512.key().build();
        final var token = Jwts.builder()
                .subject("test@example.com")
                .claim("auth", "ROLE_USER")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(otherKey, Jwts.SIG.HS512)
                .compact();

        assertThat(tokenProvider.issuedAt(token)).isEmpty();
    }

    @Test
    void aMalformedTokenHasNoIssueTime() {
        assertThat(tokenProvider.issuedAt("x.y.z")).isEmpty();
    }

    @Test
    void anExpiredTokenHasNoIssueTime() {
        final var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        final var token = Jwts.builder()
                .subject("test@example.com")
                .claim("auth", "ROLE_USER")
                .issuedAt(Date.from(Instant.now().minus(2, ChronoUnit.HOURS)))
                .expiration(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .signWith(key, Jwts.SIG.HS512)
                .compact();

        assertThat(tokenProvider.issuedAt(token)).isEmpty();
    }

    @Test
    void aNullTokenHasNoIssueTime() {
        assertThat(tokenProvider.issuedAt(null)).isEmpty();
    }
}
