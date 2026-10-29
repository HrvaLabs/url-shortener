package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.notification.ChannelTestResponse;
import cc.hrva.urlshortener.dto.notification.NotificationChannelRequest;
import cc.hrva.urlshortener.dto.notification.NotificationChannelResponse;
import cc.hrva.urlshortener.dto.notification.SigningSecretResponse;
import cc.hrva.urlshortener.service.NotificationChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/users/me/notification-channels")
public class NotificationChannelController {

    private final NotificationChannelService notificationChannelService;

    @Operation(summary = "Your notification channels", description = "The signed-in user's Discord, Slack, Telegram, ntfy and webhook channels, oldest first, each with its events, a masked address and how its last delivery went. Secrets are never returned.")
    @ApiResponse(responseCode = "200", description = "The channels")
    @GetMapping
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<List<NotificationChannelResponse>> mine() {
        return ResponseEntity.ok(notificationChannelService.mine());
    }

    @Operation(summary = "Add a notification channel", description = "Adds a channel and records consent for each event it gets. The address must be a public https URL of the right shape for the type. A webhook channel's answer carries its signing secret, shown only this once. Up to 10 channels; 60 changes per hour, shared with the email settings.")
    @ApiResponse(responseCode = "201", description = "Channel added")
    @ApiResponse(responseCode = "400", description = "A field error, an unknown event or the 10-channel limit")
    @ApiResponse(responseCode = "429", description = "Too many notification settings changes")
    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<NotificationChannelResponse> create(@Valid @RequestBody final NotificationChannelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(notificationChannelService.create(request));
    }

    @Operation(summary = "Change a notification channel", description = "Changes the name, the switch, the events and, when given, the address or tokens; empty secret fields keep the saved ones. Turning an auto-disabled channel back on clears its failed days. The type can't change.")
    @ApiResponse(responseCode = "200", description = "Channel changed")
    @ApiResponse(responseCode = "400", description = "A field error, an unknown event or a changed type")
    @ApiResponse(responseCode = "404", description = "No such channel of yours")
    @ApiResponse(responseCode = "429", description = "Too many notification settings changes")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<NotificationChannelResponse> update(@PathVariable final long id, @Valid @RequestBody final NotificationChannelRequest request) {
        return ResponseEntity.ok(notificationChannelService.update(id, request));
    }

    @Operation(summary = "Delete a notification channel", description = "Revokes the channel's consents, keeping them with the channel's name as a record, then deletes the channel and its queued deliveries.")
    @ApiResponse(responseCode = "204", description = "Channel deleted")
    @ApiResponse(responseCode = "404", description = "No such channel of yours")
    @ApiResponse(responseCode = "429", description = "Too many notification settings changes")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<Void> delete(@PathVariable final long id) {
        notificationChannelService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "New signing secret for a webhook channel", description = "Replaces a webhook channel's signing secret and returns the new one, shown only this once. Shares the limit of 10 per 10 minutes with Send test.")
    @ApiResponse(responseCode = "200", description = "The new signing secret")
    @ApiResponse(responseCode = "400", description = "Not a webhook channel")
    @ApiResponse(responseCode = "404", description = "No such channel of yours")
    @ApiResponse(responseCode = "429", description = "Too many tests or new secrets")
    @ApiResponse(responseCode = "503", description = "The limit can't be checked right now")
    @PostMapping("/{id}/signing-secret")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<SigningSecretResponse> newSigningSecret(@PathVariable final long id) {
        return ResponseEntity.ok(notificationChannelService.newSigningSecret(id));
    }

    @Operation(summary = "Send a test message to a notification channel", description = "Posts a test message to the channel now, whether it is switched on or not, and answers with how it went. It sets the channel's last delivery but never counts toward turning the channel off. 10 per 10 minutes, shared with New signing secret.")
    @ApiResponse(responseCode = "200", description = "How the test went; delivered is false when the channel refused it or couldn't be reached")
    @ApiResponse(responseCode = "404", description = "No such channel of yours")
    @ApiResponse(responseCode = "429", description = "Too many tests or new secrets")
    @ApiResponse(responseCode = "503", description = "The limit can't be checked right now")
    @PostMapping("/{id}/test")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<ChannelTestResponse> test(@PathVariable final long id) {
        return ResponseEntity.ok(notificationChannelService.test(id));
    }

}
