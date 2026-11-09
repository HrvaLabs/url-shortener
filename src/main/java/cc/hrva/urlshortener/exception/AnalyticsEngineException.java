package cc.hrva.urlshortener.exception;

public class AnalyticsEngineException extends CommonException {

    public AnalyticsEngineException(final String message) {
        super(message);
    }

    public AnalyticsEngineException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
