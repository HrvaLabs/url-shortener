package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.settings.PublicSettingsResponse;
import cc.hrva.urlshortener.service.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public")
public class PublicSettingsController {

    private static final Duration BROWSER_CACHE = Duration.ofMinutes(1);

    private final SettingsService settingsService;

    @Operation(summary = "Which features are on", description = "Whether links can be made without an account, new accounts can be created "
            + "and custom aliases can be chosen. Nothing per user.")
    @ApiResponse(responseCode = "200", description = "The public switches")
    @GetMapping("/settings")
    public ResponseEntity<PublicSettingsResponse> publicSettings() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(BROWSER_CACHE).cachePublic())
                .body(settingsService.publicSettings());
    }

}
