package cc.hrva.urlshortener.client;

import java.time.Duration;

public record PageSnapshot(Outcome outcome, byte[] image, String contentType, String title, Integer status, String failure, Duration retryAfter) {

    public static PageSnapshot taken(final byte[] image, final String contentType, final String title, final Integer status) {
        return new PageSnapshot(Outcome.TAKEN, image, contentType, title, status, null, null);
    }

    public static PageSnapshot failed(final String failure) {
        return new PageSnapshot(Outcome.FAILED, null, null, null, null, failure, null);
    }

    public static PageSnapshot rateLimited(final Duration retryAfter) {
        return new PageSnapshot(Outcome.RATE_LIMITED, null, null, null, null, null, retryAfter);
    }

    public static PageSnapshot dailyLimit() {
        return new PageSnapshot(Outcome.DAILY_LIMIT, null, null, null, null, null, null);
    }

    public static PageSnapshot notConfigured() {
        return new PageSnapshot(Outcome.NOT_CONFIGURED, null, null, null, null, null, null);
    }

    public enum Outcome {
        TAKEN,
        FAILED,
        RATE_LIMITED,
        DAILY_LIMIT,
        NOT_CONFIGURED
    }

}
