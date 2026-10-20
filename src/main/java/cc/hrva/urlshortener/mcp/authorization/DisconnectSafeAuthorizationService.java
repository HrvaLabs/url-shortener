package cc.hrva.urlshortener.mcp.authorization;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
public class DisconnectSafeAuthorizationService extends JdbcOAuth2AuthorizationService {

    private static final String LOCK_EXISTING_AUTHORIZATION = "UPDATE oauth2_authorization SET state = state WHERE id = ?";

    private final TransactionTemplate transactionTemplate;

    public DisconnectSafeAuthorizationService(final JdbcOperations jdbcOperations,
                                              final RegisteredClientRepository registeredClientRepository,
                                              final PlatformTransactionManager transactionManager) {
        super(jdbcOperations, registeredClientRepository);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void save(final OAuth2Authorization authorization) {
        if (authorization == null || authorization.getAccessToken() == null) {
            super.save(authorization);
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            if (getJdbcOperations().update(LOCK_EXISTING_AUTHORIZATION, authorization.getId()) == 0) {
                log.info("Refused to restore a removed MCP authorization authorizationId={}", authorization.getId());
                throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            }
            super.save(authorization);
        });
    }

}
