package cc.hrva.urlshortener.exception;

import java.time.Duration;
import lombok.Getter;

@Getter
public class RateLimitExceededException extends CommonException {

    private final transient Duration retryAfter;

    public RateLimitExceededException(final String message) {
        this(message, null);
    }

    public RateLimitExceededException(final String message, final Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

}
