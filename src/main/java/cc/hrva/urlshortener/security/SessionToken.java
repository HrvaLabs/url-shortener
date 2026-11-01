package cc.hrva.urlshortener.security;

import java.time.Instant;
import org.springframework.security.core.Authentication;

public record SessionToken(Authentication authentication, Instant issuedAt, Instant signedInAt, Instant expiresAt) {
}
