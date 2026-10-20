package cc.hrva.urlshortener.mcp.apps;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/users/me/connected-apps")
public class ConnectedAppsController {

    private final ConnectedAppsService connectedAppsService;

    @Operation(summary = "List connected apps", description = "AI apps connected to the authenticated user's account through the MCP connector, most recently used first.")
    @GetMapping
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<List<ConnectedAppDto>> fetchConnectedApps(final Authentication authentication) {
        return ResponseEntity.ok(connectedAppsService.findConnectedApps(authentication.getName()));
    }

    @Operation(summary = "Disconnect an app", description = "Revoke every authorization the authenticated user granted to the app. Its access and refresh tokens stop working immediately.")
    @ApiResponse(responseCode = "204", description = "App disconnected")
    @ApiResponse(responseCode = "404", description = "No connected app with this client ID")
    @DeleteMapping("/{clientId}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<Void> disconnect(@PathVariable("clientId") final String clientId, final Authentication authentication) {
        connectedAppsService.disconnect(authentication.getName(), clientId);

        return ResponseEntity.noContent().build();
    }

}
