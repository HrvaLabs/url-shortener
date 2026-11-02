package cc.hrva.urlshortener.mcp.bridge;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.ConnectRequestExpiredException;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.UserService;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/connect/requests")
public class ConnectController {

    private final ConnectRequestStore connectRequestStore;
    private final RegisteredClientRepository registeredClientRepository;
    private final UserService userService;
    private final AppProperties appProperties;
    private final ConnectDestinations connectDestinations;
    private final ConnectedAppsService connectedAppsService;
    private final ApplicationEventPublisher eventPublisher;

    @GetMapping("/{handle}")
    public ResponseEntity<ConnectRequestView> fetchConnectRequest(@PathVariable("handle") final String handle) {
        final var connectRequest = connectRequestStore.findPending(handle).orElseThrow(ConnectRequestExpiredException::new);

        return ResponseEntity.ok(connectDestinations.viewOf(clientOf(connectRequest).getClientName(), connectRequest.redirectUri()));
    }

    @PostMapping("/{handle}/approve")
    public ResponseEntity<ConnectApproval> approve(@PathVariable("handle") final String handle, final Authentication authentication) {
        final var user = userService.fetchActiveUserByEmail(authentication.getName());
        final var pending = connectRequestStore.findPending(handle);
        final var code = connectRequestStore.approve(handle, user.getEmail());
        pending.filter(connectRequest -> !alreadyConnected(user, connectRequest)).ifPresent(connectRequest -> eventPublisher.publishEvent(
                new AppConnectedEvent(user, connectDestinations.viewOf(clientOf(connectRequest).getClientName(), connectRequest.redirectUri()),
                        LocalDateTime.now(ZoneOffset.UTC))));
        final var continueUrl = UriComponentsBuilder.fromUriString(appProperties.getMcp().getIssuer())
                .path(ConnectContinueController.CONTINUE_PATH)
                .queryParam(ConnectContinueController.CODE_PARAMETER, code)
                .build()
                .toUriString();

        return ResponseEntity.ok(new ConnectApproval(continueUrl));
    }

    @PostMapping("/{handle}/deny")
    public ResponseEntity<ConnectDenial> deny(@PathVariable("handle") final String handle) {
        final var connectRequest = connectRequestStore.deny(handle);
        final var redirectTo = UriComponentsBuilder.fromUriString(connectRequest.redirectUri())
                .queryParam(OAuth2ParameterNames.ERROR, OAuth2ErrorCodes.ACCESS_DENIED);
        if (StringUtils.hasText(connectRequest.state())) {
            redirectTo.queryParam(OAuth2ParameterNames.STATE, UriUtils.encode(connectRequest.state(), StandardCharsets.UTF_8));
        }

        return ResponseEntity.ok(new ConnectDenial(redirectTo.build(true).toUriString()));
    }

    private boolean alreadyConnected(final User user, final ConnectRequest connectRequest) {
        return connectedAppsService.findConnectedApps(user.getEmail()).stream()
                .anyMatch(app -> app.clientId().equals(connectRequest.clientId()));
    }

    private RegisteredClient clientOf(final ConnectRequest connectRequest) {
        return Optional.ofNullable(registeredClientRepository.findByClientId(connectRequest.clientId()))
                .orElseThrow(ConnectRequestExpiredException::new);
    }

}
