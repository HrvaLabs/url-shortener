package cc.hrva.urlshortener.service.ratelimit;

import cc.hrva.urlshortener.exception.RateLimitExceededException;
import java.time.Duration;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEmailLimits {

    public static final int REQUESTS_PER_NETWORK = 10;
    public static final int EMAILS_PER_RECIPIENT = 3;
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final String NETWORK = "account-email:network";
    private static final String RECIPIENT = "account-email:recipient";
    private static final String TOO_MANY = "Too many emails asked for from this network. Try again in %s.";

    private final RateLimiter rateLimiter;

    public void checkNetwork(final String clientIp) {
        final RateLimitWindow window;
        try {
            window = rateLimiter.incrementWindow(RateLimiter.clientKey(NETWORK, StringUtils.defaultString(clientIp)), WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Account email network limit unavailable, allowing the request");
            return;
        }
        if (window.count() > REQUESTS_PER_NETWORK) {
            log.info("Account email request refused by the network limit retryAfterSeconds={}", window.resetsIn().toSeconds());
            throw new RateLimitExceededException(TOO_MANY.formatted(minutesOf(window.resetsIn())), window.resetsIn());
        }
    }

    public boolean allowsEmailTo(final String email) {
        try {
            return rateLimiter.tryAcquire(RateLimiter.hashedKey(RECIPIENT, StringUtils.defaultString(email).strip().toLowerCase(Locale.ROOT)),
                    EMAILS_PER_RECIPIENT, WINDOW);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Account email recipient limit unavailable, sending the email");
            return true;
        }
    }

    private static String minutesOf(final Duration wait) {
        final var minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }

}
