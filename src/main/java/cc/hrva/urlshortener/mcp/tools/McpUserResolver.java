package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class McpUserResolver {

    private final UserService userService;

    public User currentUser() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication)) {
            throw new NoAuthorizationException("Not signed in");
        }

        return userService.fetchActiveUserByEmail(authentication.getToken().getSubject());
    }

}
