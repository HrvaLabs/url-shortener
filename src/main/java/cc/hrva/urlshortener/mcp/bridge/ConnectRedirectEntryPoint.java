package cc.hrva.urlshortener.mcp.bridge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@RequiredArgsConstructor
public class ConnectRedirectEntryPoint implements AuthenticationEntryPoint {

    public static final String HANDLE_ATTRIBUTE = ConnectRedirectEntryPoint.class.getName() + ".HANDLE";

    private static final String VALIDATED_AUTHORIZATION_REQUEST_ATTRIBUTE = OAuth2AuthorizationCodeRequestAuthenticationToken.class.getName();
    private static final String CONNECT_PAGE_PATH = "/connect";
    private static final String HANDLE_PARAMETER = "request";

    private final ConnectRequestStore connectRequestStore;
    private final RegisteredClientRepository registeredClientRepository;
    private final String frontendUrl;

    @Override
    public void commence(final HttpServletRequest request,
                         final HttpServletResponse response,
                         final AuthenticationException authenticationException) throws IOException {
        if (!HttpMethod.GET.matches(request.getMethod())
                || !StringUtils.hasText(request.getQueryString())
                || !(request.getAttribute(VALIDATED_AUTHORIZATION_REQUEST_ATTRIBUTE) instanceof OAuth2AuthorizationCodeRequestAuthenticationToken authorizationRequest)) {
            response.sendError(HttpStatus.BAD_REQUEST.value());
            return;
        }

        final var registeredClient = registeredClientRepository.findByClientId(authorizationRequest.getClientId());
        if (registeredClient == null) {
            response.sendError(HttpStatus.BAD_REQUEST.value());
            return;
        }

        final var redirectUri = StringUtils.hasText(authorizationRequest.getRedirectUri())
                ? authorizationRequest.getRedirectUri()
                : registeredClient.getRedirectUris().iterator().next();
        final String handle;
        try {
            handle = connectRequestStore.save(new ConnectRequest(
                    registeredClient.getClientId(),
                    redirectUri,
                    authorizationRequest.getState(),
                    request.getQueryString()
            ));
        } catch (final DataAccessException exception) {
            log.warn("Could not store a connect request: {}", exception.getClass().getSimpleName());
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value());
            return;
        }

        final var session = request.getSession();
        session.setMaxInactiveInterval((int) ConnectRequestStore.REQUEST_TIME_TO_LIVE.toSeconds());
        session.setAttribute(HANDLE_ATTRIBUTE, handle);

        response.sendRedirect(UriComponentsBuilder.fromUriString(frontendUrl)
                .path(CONNECT_PAGE_PATH)
                .queryParam(HANDLE_PARAMETER, handle)
                .build()
                .toUriString());
    }

}
