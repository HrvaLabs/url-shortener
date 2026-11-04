package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.codebook.Authorities;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class TokenProvider {

  private static final String AUTHORITIES_KEY = "auth";
  private static final String SIGNED_IN_AT_KEY = "auth_time";
  private static final Long SECONDS_TO_MILLISECONDS = 1000L;

  private final AppProperties appProperties;

  private SecretKey key;
  private long tokenValidityInMilliseconds;

  @PostConstruct
  public void init() {
    final var keyBytes = Decoders.BASE64.decode(appProperties.getJwtBase64Secret());
    this.key = Keys.hmacShaKeyFor(keyBytes);
    this.tokenValidityInMilliseconds = SECONDS_TO_MILLISECONDS * appProperties.getJwtTokenValiditySeconds();
  }

  public String createToken(final Authentication authentication) {
    return createToken(authentication, appProperties.getJwtTokenValiditySeconds());
  }

  public String createToken(final Authentication authentication, final long ttlSeconds) {
    final var now = Instant.ofEpochMilli(new Date().getTime());

    return signedToken(authentication, now, now, now.plusMillis(SECONDS_TO_MILLISECONDS * ttlSeconds));
  }

  public String renewToken(final Authentication authentication, final SessionToken current) {
    final var now = Instant.now();
    final var signedInAt = Objects.requireNonNullElse(current.signedInAt(), now);

    return signedToken(authentication, now, signedInAt, renewedExpiry(signedInAt, current.expiresAt(), now));
  }

  private Instant renewedExpiry(final Instant signedInAt, final Instant expiresAt, final Instant now) {
    final var longest = signedInAt.plusSeconds(appProperties.getJwtSessionMaxSeconds());
    final var sliding = now.plusMillis(tokenValidityInMilliseconds);
    final var capped = sliding.isAfter(longest) ? longest : sliding;

    return expiresAt != null && expiresAt.isAfter(capped) ? expiresAt : capped;
  }

  public static Authentication authenticationOf(final cc.hrva.urlshortener.model.User user) {
    return new UsernamePasswordAuthenticationToken(user.getEmail(), null, authoritiesOf(user));
  }

  public static Authentication authenticationOf(final cc.hrva.urlshortener.model.User user, final String token) {
    final var authorities = AdminTwoStep.sessionAuthoritiesOf(user, authoritiesOf(user));

    return new UsernamePasswordAuthenticationToken(new User(user.getEmail(), "", authorities), token, authorities);
  }

  public Authentication getAuthentication(final String token) {
    return authenticationOf(claimsOf(token), token);
  }

  public Optional<SessionToken> read(final String token) {
    try {
      final var claims = claimsOf(token);
      final var issuedAt = Optional.ofNullable(claims.getIssuedAt()).map(Date::toInstant).orElse(null);

      return Optional.of(new SessionToken(authenticationOf(claims, token), issuedAt, signedInAtOf(claims, issuedAt),
              Optional.ofNullable(claims.getExpiration()).map(Date::toInstant).orElse(null)));
    } catch (final JwtException | IllegalArgumentException e) {
      log.info("Invalid JWT token.");
      log.trace("Invalid JWT token trace.", e);
      return Optional.empty();
    }
  }

  public Optional<Instant> issuedAt(final String token) {
    return read(token).map(SessionToken::issuedAt);
  }

  public Optional<Instant> signedInAt(final String token) {
    try {
      final var claims = claimsOf(token);

      return Optional.ofNullable(signedInAtOf(claims, Optional.ofNullable(claims.getIssuedAt()).map(Date::toInstant).orElse(null)));
    } catch (final JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  public boolean validateToken(final String authToken) {
    try {
      Jwts.parser()
          .verifyWith(key)
          .build()
          .parseSignedClaims(authToken);

      return true;
    } catch (final JwtException | IllegalArgumentException e) {
      log.info("Invalid JWT token.");
      log.trace("Invalid JWT token trace.", e);
    }

    return false;
  }

  private String signedToken(final Authentication authentication, final Instant issuedAt, final Instant signedInAt, final Instant expiresAt) {
    final var authorities = authentication.getAuthorities().stream()
                                          .map(GrantedAuthority::getAuthority)
                                          .collect(Collectors.joining(","));

    return Jwts.builder()
               .subject(authentication.getName())
               .claim(AUTHORITIES_KEY, authorities)
               .claim(SIGNED_IN_AT_KEY, signedInAt.getEpochSecond())
               .issuedAt(Date.from(issuedAt))
               .expiration(Date.from(expiresAt))
               .signWith(key, Jwts.SIG.HS512)
               .compact();
  }

  private Claims claimsOf(final String token) {
    return Jwts.parser()
               .verifyWith(key)
               .build()
               .parseSignedClaims(token)
               .getPayload();
  }

  private static Authentication authenticationOf(final Claims claims, final String token) {
    final var authorities = Arrays.stream(Objects.toString(claims.get(AUTHORITIES_KEY), "").split(","))
                                  .filter(authority -> !authority.isBlank())
                                  .map(SimpleGrantedAuthority::new)
                                  .toList();

    final var principal = new User(claims.getSubject(), "", authorities);

    return new UsernamePasswordAuthenticationToken(principal, token, authorities);
  }

  private static List<SimpleGrantedAuthority> authoritiesOf(final cc.hrva.urlshortener.model.User user) {
    return Objects.requireNonNullElse(user.getAuthorities(), List.<Authorities>of()).stream()
               .map(authority -> new SimpleGrantedAuthority(authority.getName()))
               .toList();
  }

  private static Instant signedInAtOf(final Claims claims, final Instant issuedAt) {
    final var signedInAt = claims.get(SIGNED_IN_AT_KEY);

    return signedInAt instanceof Number seconds ? Instant.ofEpochSecond(seconds.longValue()) : issuedAt;
  }

}
