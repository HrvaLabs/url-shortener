package cc.hrva.urlshortener.client;

import java.time.Duration;

public record PageSnapshot(Outcome outcome, byte[] image, String contentType, String title, Integer status, String failure, Duration retryAfter,
                           Long browserMsUsed) {

    public static PageSnapshot taken(final byte[] image, final String contentType, final String title, final Integer status) {
        return new PageSnapshot(Outcome.TAKEN, image, contentType, title, status, null, null, null);
    }

    public static PageSnapshot failed(final String failure) {
        return new PageSnapshot(Outcome.FAILED, null, null, null, null, failure, null, null);
    }

    public static PageSnapshot rateLimited(final Duration retryAfter) {
        return new PageSnapshot(Outcome.RATE_LIMITED, null, null, null, null, null, retryAfter, null);
    }

    public static PageSnapshot dailyLimit() {
        return new PageSnapshot(Outcome.DAILY_LIMIT, null, null, null, null, null, null, null);
    }

    public static PageSnapshot tokenRefused() {
        return new PageSnapshot(Outcome.TOKEN_REFUSED, null, null, null, null, null, null, null);
    }

    public static PageSnapshot notConfigured() {
        return new PageSnapshot(Outcome.NOT_CONFIGURED, null, null, null, null, null, null, null);
    }

    public static PageSnapshot interrupted() {
        return new PageSnapshot(Outcome.INTERRUPTED, null, null, null, null, null, null, null);
    }

    public PageSnapshot withBrowserMsUsed(final Long browserMsUsed) {
        return new PageSnapshot(outcome, image, contentType, title, status, failure, retryAfter, browserMsUsed);
    }

    public enum Outcome {
        TAKEN,
        FAILED,
        RATE_LIMITED,
        DAILY_LIMIT,
        TOKEN_REFUSED,
        INTERRUPTED,
        NOT_CONFIGURED
    }

}
