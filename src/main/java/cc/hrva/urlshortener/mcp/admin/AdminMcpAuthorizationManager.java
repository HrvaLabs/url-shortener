package cc.hrva.urlshortener.mcp.admin;

import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.util.DatabaseOutages;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@Slf4j
@RequiredArgsConstructor
public class AdminMcpAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final AuthorizationDecision ALLOWED = new AuthorizationDecision(true);
    private static final AuthorizationDecision REFUSED = new AuthorizationDecision(false);

    private final UserRepository userRepository;

    @Override
    public AuthorizationResult authorize(final Supplier<? extends Authentication> authentication, final RequestAuthorizationContext context) {
        if (!(authentication.get() instanceof JwtAuthenticationToken token)) {
            return REFUSED;
        }

        try {
            return decide(token);
        } catch (final DataAccessException exception) {
            if (DatabaseOutages.isOutage(exception)) {
                throw exception;
            }
            log.warn("MCP admin endpoint refused, the account couldn't be read exception={}", exception.getClass().getName());
            return REFUSED;
        }
    }

    private AuthorizationResult decide(final JwtAuthenticationToken token) {
        final var user = userRepository.findByEmail(token.getToken().getSubject())
                .filter(candidate -> !Boolean.FALSE.equals(candidate.getActive()))
                .orElse(null);
        if (user == null) {
            log.info("MCP admin endpoint refused an unknown or inactive account");
            return REFUSED;
        }
        if (!userRepository.existsByIdAndAuthoritiesName(user.getId(), ROLE_ADMIN)) {
            log.info("MCP admin endpoint refused a non-admin userId={}", user.getId());
            return REFUSED;
        }
        if (!Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            log.info("MCP admin endpoint refused, two-step sign-in is off userId={}", user.getId());
            return REFUSED;
        }

        return ALLOWED;
    }

}
