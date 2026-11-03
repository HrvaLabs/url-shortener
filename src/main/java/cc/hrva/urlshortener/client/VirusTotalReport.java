package cc.hrva.urlshortener.client;

import java.time.LocalDateTime;

public record VirusTotalReport(Outcome outcome, String reportId, Integer malicious, Integer suspicious, Integer harmless, Integer undetected,
                               LocalDateTime analysedAt, String failure) {

    public static final String KEY_REFUSED = "VirusTotal refused the API key.";

    public static VirusTotalReport found(final String reportId, final Integer malicious, final Integer suspicious, final Integer harmless,
                                         final Integer undetected, final LocalDateTime analysedAt) {
        return new VirusTotalReport(Outcome.FOUND, reportId, malicious, suspicious, harmless, undetected, analysedAt, null);
    }

    public static VirusTotalReport notFound() {
        return new VirusTotalReport(Outcome.NOT_FOUND, null, null, null, null, null, null, null);
    }

    public static VirusTotalReport submitted() {
        return new VirusTotalReport(Outcome.SUBMITTED, null, null, null, null, null, null, null);
    }

    public static VirusTotalReport rateLimited() {
        return new VirusTotalReport(Outcome.RATE_LIMITED, null, null, null, null, null, null, null);
    }

    public static VirusTotalReport failed(final String failure) {
        return new VirusTotalReport(Outcome.FAILED, null, null, null, null, null, null, failure);
    }

    public static VirusTotalReport notConfigured() {
        return new VirusTotalReport(Outcome.NOT_CONFIGURED, null, null, null, null, null, null, null);
    }

    public boolean keyRefused() {
        return outcome == Outcome.FAILED && KEY_REFUSED.equals(failure);
    }

    public enum Outcome {
        FOUND,
        NOT_FOUND,
        SUBMITTED,
        RATE_LIMITED,
        FAILED,
        NOT_CONFIGURED
    }

}
