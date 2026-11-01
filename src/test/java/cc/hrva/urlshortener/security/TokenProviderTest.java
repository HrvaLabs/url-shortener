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

        assertThat(tokenProvider.read(token).map(SessionToken::issuedAt))
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

        assertThat(tokenProvider.read(token).map(SessionToken::issuedAt)).isEmpty();
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

        assertThat(tokenProvider.read(token).map(SessionToken::issuedAt)).isEmpty();
    }

    @Test
    void aMalformedTokenHasNoIssueTime() {
        assertThat(tokenProvider.read("x.y.z").map(SessionToken::issuedAt)).isEmpty();
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

        assertThat(tokenProvider.read(token).map(SessionToken::issuedAt)).isEmpty();
    }

    @Test
    void aNullTokenHasNoIssueTime() {
        assertThat(tokenProvider.read(null).map(SessionToken::issuedAt)).isEmpty();
    }

    @Test
    void readingATokenParsesItOnceIntoItsUserTimesAndExpiry() {
        final var authentication = userAuthentication();
        final var before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        final var read = tokenProvider.read(tokenProvider.createToken(authentication, 60)).orElseThrow();

        assertThat(read.authentication().getName()).isEqualTo("test@example.com");
        assertThat(read.authentication().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        assertThat(read.issuedAt()).isBetween(before, Instant.now());
        assertThat(read.signedInAt()).isEqualTo(read.issuedAt());
        assertThat(read.expiresAt()).isEqualTo(read.issuedAt().plusSeconds(60));
    }

    @Test
    void aTokenSignedWithAnotherKeyCantBeRead() {
        final var token = Jwts.builder()
                .subject("test@example.com")
                .claim("auth", "ROLE_USER")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(Jwts.SIG.HS512.key().build(), Jwts.SIG.HS512)
                .compact();

        assertThat(tokenProvider.read(token)).isEmpty();
        assertThat(tokenProvider.read("x.y.z")).isEmpty();
        assertThat(tokenProvider.read(null)).isEmpty();
    }

    @Test
    void aRenewedTokenIsIssuedNowButKeepsTheSignInTimeAndTheExpiry() {
        final var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        final var signedIn = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        final var expires = Instant.now().plus(5, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        final var old = Jwts.builder()
                .subject("test@example.com")
                .claim("auth", "ROLE_USER,ROLE_ADMIN")
                .issuedAt(Date.from(signedIn))
                .expiration(Date.from(expires))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
        final var oldToken = tokenProvider.read(old).orElseThrow();

        final var renewed = tokenProvider.read(tokenProvider.renewToken(userAuthentication(), oldToken)).orElseThrow();

        assertThat(renewed.issuedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
        assertThat(renewed.signedInAt()).isEqualTo(signedIn);
        assertThat(renewed.expiresAt()).isEqualTo(expires);
        assertThat(renewed.authentication().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        assertThat(tokenProvider.signedInAt(tokenProvider.renewToken(userAuthentication(), oldToken))).contains(signedIn);
    }

    @Test
    void theSignInTimeOfATokenWithoutOneIsWhenItWasIssued() {
        final var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
        final var issued = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        final var token = Jwts.builder()
                .subject("test@example.com")
                .claim("auth", "ROLE_USER")
                .issuedAt(Date.from(issued))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key, Jwts.SIG.HS512)
                .compact();

        assertThat(tokenProvider.signedInAt(token)).contains(issued);
        assertThat(tokenProvider.signedInAt("x.y.z")).isEmpty();
    }

    private static Authentication userAuthentication() {
        return new UsernamePasswordAuthenticationToken("test@example.com", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
