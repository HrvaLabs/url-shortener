package cc.hrva.urlshortener.mcp.bridge;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import cc.hrva.urlshortener.util.ProblemResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
public class ConnectContinueController {

    public static final String CONTINUE_PATH = "/oauth2/connect/continue";
    public static final String CODE_PARAMETER = "otc";

    private static final String AUTHORIZATION_ENDPOINT = "/oauth2/authorize";
    private static final String USER_AUTHORITY = "ROLE_USER";
    private static final Duration AUTHORIZED_SESSION_TIME_TO_LIVE = Duration.ofSeconds(60);

    private final ConnectRequestStore connectRequestStore;
    private final AppProperties appProperties;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy securityContextHolderStrategy = SecurityContextHolder.getContextHolderStrategy();

    @GetMapping(CONTINUE_PATH)
    public ResponseEntity<ProblemDetail> continueAuthorization(@RequestParam(name = CODE_PARAMETER, required = false) final String code,
                                                                     final HttpServletRequest request,
                                                                     final HttpServletResponse response) {
        final var redeemedRequest = Optional.ofNullable(code)
                .filter(StringUtils::hasText)
                .flatMap(connectRequestStore::redeem);
        if (redeemedRequest.isEmpty()) {
            return ProblemResponses.entity(HttpStatus.BAD_REQUEST, "invalid_code");
        }

        final var approvedConnectRequest = redeemedRequest.get();
        final var session = request.getSession(false);
        if (session == null || !approvedConnectRequest.handle().equals(session.getAttribute(ConnectRedirectEntryPoint.HANDLE_ATTRIBUTE))) {
            return ProblemResponses.entity(HttpStatus.FORBIDDEN, "session_mismatch");
        }

        session.removeAttribute(ConnectRedirectEntryPoint.HANDLE_ATTRIBUTE);
        request.changeSessionId();
        session.setMaxInactiveInterval((int) AUTHORIZED_SESSION_TIME_TO_LIVE.toSeconds());
        session.setAttribute(BridgedAuthorizationRequestFilter.AUTHORIZATION_QUERY_ATTRIBUTE, approvedConnectRequest.authorizationQuery());
        final var securityContext = securityContextHolderStrategy.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                approvedConnectRequest.email(),
                null,
                List.of(new SimpleGrantedAuthority(USER_AUTHORITY))
        ));
        securityContextRepository.saveContext(securityContext, request, response);

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, appProperties.getMcp().getIssuer() + AUTHORIZATION_ENDPOINT + "?" + approvedConnectRequest.authorizationQuery())
                .build();
    }

}
