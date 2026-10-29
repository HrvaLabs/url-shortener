package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.notification.NotificationConsentDto;
import cc.hrva.urlshortener.dto.notification.NotificationConsentResponse;
import cc.hrva.urlshortener.service.NotificationConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/users/me/notifications")
public class NotificationController {

    private final NotificationConsentService notificationConsentService;

    @Operation(summary = "Optional emails and their consent", description = "The three optional emails (DEAD_LINK_EMAIL, WEEKLY_SUMMARY_EMAIL, EXPIRY_REMINDER_EMAIL), always in that order, each with whether the signed-in user turned it on, when, and a one-line description. All are off until the user turns them on.")
    @ApiResponse(responseCode = "200", description = "The three optional emails")
    @GetMapping
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<List<NotificationConsentResponse>> mine() {
        return ResponseEntity.ok(notificationConsentService.mine());
    }

    @Operation(summary = "Turn an optional email on or off", description = "Records the consent: turning on adds a consent record, turning off marks it revoked and keeps it. Turning on what is on, or off what is off, changes nothing. 60 changes per hour per user, shared with notification channels.")
    @ApiResponse(responseCode = "200", description = "The email's new state")
    @ApiResponse(responseCode = "400", description = "Unknown email type, or consented missing")
    @ApiResponse(responseCode = "429", description = "Too many notification settings changes")
    @PutMapping("/{type}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<NotificationConsentResponse> set(@PathVariable final String type, @Valid @RequestBody final NotificationConsentDto dto) {
        return ResponseEntity.ok(notificationConsentService.set(type, dto.getConsented()));
    }

}
