package cc.hrva.urlshortener.exception;

public class RateLimitExceededException extends CommonException {

    public RateLimitExceededException(final String message) {
        super(message);
    }

}
