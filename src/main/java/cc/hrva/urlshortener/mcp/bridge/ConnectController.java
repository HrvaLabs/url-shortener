package cc.hrva.urlshortener.mcp.bridge;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.ConnectRequestExpiredException;
import cc.hrva.urlshortener.service.UserService;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
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

    @GetMapping("/{handle}")
    public ResponseEntity<ConnectRequestView> fetchConnectRequest(@PathVariable("handle") final String handle) {
        final var registeredClient = connectRequestStore.findPending(handle)
                .map(connectRequest -> registeredClientRepository.findByClientId(connectRequest.clientId()))
                .orElseThrow(ConnectRequestExpiredException::new);

        return ResponseEntity.ok(new ConnectRequestView(registeredClient.getClientName()));
    }

    @PostMapping("/{handle}/approve")
    public ResponseEntity<ConnectApproval> approve(@PathVariable("handle") final String handle, final Authentication authentication) {
        final var user = userService.fetchActiveUserByEmail(authentication.getName());
        final var code = connectRequestStore.approve(handle, user.getEmail());
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

}
