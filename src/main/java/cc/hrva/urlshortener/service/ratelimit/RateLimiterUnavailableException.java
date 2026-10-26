package cc.hrva.urlshortener.service.ratelimit;

public class RateLimiterUnavailableException extends RuntimeException {

    public RateLimiterUnavailableException(final Throwable cause) {
        super("Rate limiter unavailable", cause);
    }

}
