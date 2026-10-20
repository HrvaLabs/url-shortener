package cc.hrva.urlshortener.mcp.apps;

import cc.hrva.urlshortener.exception.ConnectedAppNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultConnectedAppsService implements ConnectedAppsService {

    private static final String PRINCIPAL_NAME = "principalName";
    private static final String CLIENT_ID = "clientId";
    private static final String CONNECTED_APPS = """
            SELECT client_id, client_name, first_connected, last_used
            FROM (
                SELECT c.client_id AS client_id,
                       c.client_name AS client_name,
                       MIN(COALESCE(a.authorization_code_issued_at, a.access_token_issued_at, a.refresh_token_issued_at)) AS first_connected,
                       MAX(a.access_token_issued_at) AS last_used
                FROM oauth2_authorization a
                JOIN oauth2_registered_client c ON c.id = a.registered_client_id
                WHERE a.principal_name = :principalName
                  AND (a.access_token_value IS NOT NULL OR a.refresh_token_value IS NOT NULL)
                GROUP BY c.id, c.client_id, c.client_name
            ) connected_apps
            ORDER BY COALESCE(last_used, first_connected) DESC, client_id
            """;
    private static final String DELETE_CLIENT_AUTHORIZATIONS = """
            DELETE FROM oauth2_authorization
            WHERE principal_name = :principalName
              AND registered_client_id IN (SELECT id FROM oauth2_registered_client WHERE client_id = :clientId)
            """;
    private static final String DELETE_CLIENT_CONSENT = """
            DELETE FROM oauth2_authorization_consent
            WHERE principal_name = :principalName
              AND registered_client_id IN (SELECT id FROM oauth2_registered_client WHERE client_id = :clientId)
            """;
    private static final String DELETE_ALL_AUTHORIZATIONS = "DELETE FROM oauth2_authorization WHERE principal_name = :principalName";
    private static final String DELETE_ALL_CONSENTS = "DELETE FROM oauth2_authorization_consent WHERE principal_name = :principalName";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public List<ConnectedAppDto> findConnectedApps(final String principalName) {
        return jdbcTemplate.query(CONNECTED_APPS, Map.of(PRINCIPAL_NAME, principalName), DefaultConnectedAppsService::toConnectedApp);
    }

    @Override
    @Transactional
    public void disconnect(final String principalName, final String clientId) {
        final var parameters = Map.of(PRINCIPAL_NAME, principalName, CLIENT_ID, clientId);
        final var authorizations = jdbcTemplate.update(DELETE_CLIENT_AUTHORIZATIONS, parameters);
        final var consents = jdbcTemplate.update(DELETE_CLIENT_CONSENT, parameters);

        if (authorizations + consents == 0) {
            throw new ConnectedAppNotFoundException();
        }

        log.info("Disconnected an MCP client clientId={} authorizations={}", clientId, authorizations);
    }

    @Override
    @Transactional
    public int disconnectAll(final String principalName) {
        if (!StringUtils.hasText(principalName)) {
            return 0;
        }

        final var parameters = Map.of(PRINCIPAL_NAME, principalName);
        final var authorizations = jdbcTemplate.update(DELETE_ALL_AUTHORIZATIONS, parameters);
        jdbcTemplate.update(DELETE_ALL_CONSENTS, parameters);

        return authorizations;
    }

    private static ConnectedAppDto toConnectedApp(final ResultSet resultSet, final int rowNumber) throws SQLException {
        return new ConnectedAppDto(
                resultSet.getString("client_id"),
                resultSet.getString("client_name"),
                toInstant(resultSet.getTimestamp("first_connected")),
                toInstant(resultSet.getTimestamp("last_used")));
    }

    private static Instant toInstant(final Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

}
