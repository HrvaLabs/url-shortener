package cc.hrva.urlshortener.mcp.apps;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DcrClientCleanupJob {

    private static final Duration UNUSED_CLIENT_RETENTION = Duration.ofDays(30);
    private static final String NOW = "now";
    private static final String ISSUED_BEFORE = "issuedBefore";
    private static final String DELETE_EXPIRED_AUTHORIZATIONS = """
            DELETE FROM oauth2_authorization
            WHERE (authorization_code_expires_at IS NULL OR authorization_code_expires_at < :now)
              AND (access_token_expires_at IS NULL OR access_token_expires_at < :now)
              AND (refresh_token_expires_at IS NULL OR refresh_token_expires_at < :now)
              AND (oidc_id_token_expires_at IS NULL OR oidc_id_token_expires_at < :now)
              AND (user_code_expires_at IS NULL OR user_code_expires_at < :now)
              AND (device_code_expires_at IS NULL OR device_code_expires_at < :now)
            """;
    private static final String UNUSED_CLIENT = """
            c.client_id_issued_at < :issuedBefore
            AND NOT EXISTS (SELECT 1 FROM oauth2_authorization a WHERE a.registered_client_id = c.id)
            """;
    private static final String DELETE_CONSENTS_OF_UNUSED_CLIENTS =
            "DELETE FROM oauth2_authorization_consent WHERE registered_client_id IN (SELECT c.id FROM oauth2_registered_client c WHERE "
                    + UNUSED_CLIENT + ")";
    private static final String DELETE_UNUSED_CLIENTS = "DELETE FROM oauth2_registered_client c WHERE " + UNUSED_CLIENT;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional
    @Scheduled(cron = "0 30 3 * * *")
    public void deleteStaleAuthorizationsAndClients() {
        final var now = Instant.now();
        final var expiredAuthorizations = jdbcTemplate.update(DELETE_EXPIRED_AUTHORIZATIONS, Map.of(NOW, Timestamp.from(now)));
        final var unusedClientParameters = Map.of(ISSUED_BEFORE, Timestamp.from(now.minus(UNUSED_CLIENT_RETENTION)));
        jdbcTemplate.update(DELETE_CONSENTS_OF_UNUSED_CLIENTS, unusedClientParameters);
        final var unusedClients = jdbcTemplate.update(DELETE_UNUSED_CLIENTS, unusedClientParameters);

        log.info("MCP authorization cleanup expiredAuthorizations={} unusedClients={}", expiredAuthorizations, unusedClients);
    }

}
