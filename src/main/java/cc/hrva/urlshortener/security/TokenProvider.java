package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
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
    final var now = (new Date()).getTime();
    final var validity = new Date(now + SECONDS_TO_MILLISECONDS * ttlSeconds);
    final var authorities = authentication.getAuthorities().stream()
                                          .map(GrantedAuthority::getAuthority)
                                          .collect(Collectors.joining(","));

    return Jwts.builder()
               .subject(authentication.getName())
               .claim(AUTHORITIES_KEY, authorities)
               .issuedAt(new Date(now))
               .expiration(validity)
               .signWith(key, Jwts.SIG.HS512)
               .compact();
  }

  public Authentication getAuthentication(final String token) {
    final var claims = Jwts.parser()
                           .verifyWith(key)
                           .build()
                           .parseSignedClaims(token)
                           .getPayload();

    final var authorities = Arrays.stream(claims.get(AUTHORITIES_KEY).toString().split(","))
                                  .map(SimpleGrantedAuthority::new)
                                  .toList();

    final var principal = new User(claims.getSubject(), "", authorities);

    return new UsernamePasswordAuthenticationToken(principal, token, authorities);
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

  public Optional<Instant> issuedAt(final String token) {
    try {
      final var claims = Jwts.parser()
                             .verifyWith(key)
                             .build()
                             .parseSignedClaims(token)
                             .getPayload();

      return Optional.ofNullable(claims.getIssuedAt()).map(Date::toInstant);
    } catch (final JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

}
