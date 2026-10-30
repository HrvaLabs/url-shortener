package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.client.CloudflareClient;
import cc.hrva.urlshortener.client.CloudflareResult;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.enums.CloudflareTokenStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CloudflareTokenCheck {

    private final CloudflareClient cloudflareClient;
    private final AppProperties appProperties;
    private final Clock clock;
    private final AtomicReference<TokenState> state = new AtomicReference<>();

    public TokenState check() {
        final var checked = run();
        state.set(checked);
        log.info("Cloudflare token checked status={} cloudflareTotal={}", checked.status(), checked.cloudflareTotal());
        return checked;
    }

    public TokenState current() {
        final var checked = state.get();
        if (checked != null) {
            return checked;
        }
        final var status = configured() ? CloudflareTokenStatus.UNREACHABLE : CloudflareTokenStatus.MISSING;
        return new TokenState(status, null, messageFor(status), null);
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void checkAtStartup() {
        try {
            check();
        } catch (final RuntimeException exception) {
            log.warn("Cloudflare token check at startup failed exception={}", exception.getClass().getName());
        }
    }

    static String messageFor(final CloudflareTokenStatus status) {
        return switch (status) {
            case OK -> null;
            case MISSING -> "CLOUDFLARE_API_TOKEN or CLOUDFLARE_ZONE_ID isn't set.";
            case INVALID -> "Cloudflare doesn't accept the token.";
            case NO_PERMISSION -> "The token works but can't read custom hostnames on this zone.";
            case UNREACHABLE -> "Cloudflare couldn't be reached. We'll check again.";
        };
    }

    private TokenState run() {
        if (!configured()) {
            return state(CloudflareTokenStatus.MISSING, null);
        }
        final var verifiedActive = cloudflareClient.verifyToken() instanceof CloudflareResult.Ok<Boolean>(final var active, final var ignored)
                && Boolean.TRUE.equals(active);
        return switch (cloudflareClient.totalCount()) {
            case CloudflareResult.Ok<Integer>(final var total, final var ignored) -> state(CloudflareTokenStatus.OK, total);
            case CloudflareResult.Refused<Integer>(final var status, final var code, final var message)
                    when status == HttpStatus.UNAUTHORIZED.value() -> state(CloudflareTokenStatus.INVALID, null);
            case CloudflareResult.Refused<Integer> refused ->
                    state(verifiedActive ? CloudflareTokenStatus.NO_PERMISSION : CloudflareTokenStatus.INVALID, null);
            case CloudflareResult.NotFound<Integer> notFound ->
                    state(verifiedActive ? CloudflareTokenStatus.NO_PERMISSION : CloudflareTokenStatus.INVALID, null);
            case CloudflareResult.Unreachable<Integer> unreachable -> state(CloudflareTokenStatus.UNREACHABLE, null);
        };
    }

    private boolean configured() {
        final var settings = appProperties.getCloudflare();
        return StringUtils.isNoneBlank(settings.getApiToken(), settings.getZoneId());
    }

    private TokenState state(final CloudflareTokenStatus status, final Integer cloudflareTotal) {
        return new TokenState(status, LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS), messageFor(status), cloudflareTotal);
    }

    public record TokenState(CloudflareTokenStatus status, LocalDateTime checkedAt, String message, Integer cloudflareTotal) {
    }

}
