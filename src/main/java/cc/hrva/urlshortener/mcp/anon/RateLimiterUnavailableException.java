package cc.hrva.urlshortener.mcp.anon;

public class RateLimiterUnavailableException extends RuntimeException {

    public RateLimiterUnavailableException(final Throwable cause) {
        super("Rate limiter unavailable", cause);
    }

}
