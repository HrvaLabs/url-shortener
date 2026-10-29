package cc.hrva.urlshortener.client;

public record ProbeResult(ProbeOutcome outcome, String code, Integer httpStatus) {
}
