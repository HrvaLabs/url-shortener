package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AdminTwoStep;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AdminGate {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ADMINS_ONLY = "Moderation tools are for hrva.cc admins only.";

    private AdminGate() {}

    public static User requireAdmin(final McpUserResolver mcpUserResolver, final UserRepository userRepository, final String toolName) {
        final var user = mcpUserResolver.currentUser();
        if (!userRepository.existsByIdAndAuthoritiesName(user.getId(), ROLE_ADMIN)) {
            log.info("MCP moderation refused tool={} userId={}", toolName, user.getId());
            throw new NoAuthorizationException(ADMINS_ONLY);
        }
        if (!Boolean.TRUE.equals(user.getTwoStepEnabled())) {
            log.info("MCP moderation refused, two-step sign-in is off tool={} userId={}", toolName, user.getId());
            throw new NoAuthorizationException(AdminTwoStep.REQUIRED);
        }
        return user;
    }

}
