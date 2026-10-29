package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.notification.UnsubscribeResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.NotificationConsentService;
import cc.hrva.urlshortener.service.notification.UnsubscribeTokens;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(UnsubscribeController.UNSUBSCRIBE_ENDPOINT)
public class UnsubscribeController {

    public static final String UNSUBSCRIBE_ENDPOINT = "/api/v1/notifications/unsubscribe";

    private static final String RATE_LIMIT_NAME = "unsubscribe";
    private static final int TRIES_PER_NETWORK = 30;
    private static final Duration TRY_WINDOW = Duration.ofMinutes(10);
    private static final String UNSUBSCRIBE_PAGE = "/unsubscribe?token=";
    private static final String INVALID_TOKEN = "This unsubscribe link isn't valid. You can turn emails off in Settings, under Notifications.";
    private static final String TOO_MANY_TRIES = "Too many tries from your network. Try again in a few minutes.";

    private final UnsubscribeTokens unsubscribeTokens;
    private final NotificationConsentService notificationConsentService;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final HttpServletRequest request;
    private final AppProperties appProperties;

    @Operation(summary = "Open an unsubscribe link", description = "Redirects (302) to the website's confirm page with the same token. It never turns anything off, so link scanners that open the link change nothing.")
    @ApiResponse(responseCode = "302", description = "Redirect to the confirm page")
    @GetMapping
    public RedirectView open(@RequestParam final String token) {
        final var redirect = new RedirectView(StringUtils.removeEnd(StringUtils.defaultString(appProperties.getFrontendUrl()), "/")
                + UNSUBSCRIBE_PAGE + URLEncoder.encode(token, StandardCharsets.UTF_8));
        redirect.setStatusCode(HttpStatus.FOUND);
        redirect.setExposeModelAttributes(false);
        return redirect;
    }

    @Operation(summary = "Unsubscribe from an optional email", description = "Turns off the one email type the signed token names, without signing in; the body is ignored, so RFC 8058 one-click clients (List-Unsubscribe=One-Click) work. Answering again, or for an account that no longer exists, says alreadyOff. 30 tries per 10 minutes per network.")
    @ApiResponse(responseCode = "200", description = "The email type and whether it was already off")
    @ApiResponse(responseCode = "400", description = "The token isn't valid")
    @ApiResponse(responseCode = "429", description = "Too many tries from this network")
    @PostMapping
    public ResponseEntity<UnsubscribeResponse> unsubscribe(@RequestParam(required = false) final String token) {
        if (!withinLimit()) {
            throw new RateLimitExceededException(TOO_MANY_TRIES);
        }

        final var target = unsubscribeTokens.read(token).orElseThrow(() -> new ApiException(INVALID_TOKEN));
        final var revoked = notificationConsentService.revokeByUnsubscribe(target.userId(), target.type());

        return ResponseEntity.ok(new UnsubscribeResponse(target.type(), !revoked));
    }

    private boolean withinLimit() {
        try {
            return rateLimiter.tryAcquire(RateLimiter.clientKey(RATE_LIMIT_NAME, clientIpResolver.getClientIp(request)), TRIES_PER_NETWORK, TRY_WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Unsubscribe rate limit unavailable, allowing the request");
            return true;
        }
    }

}
