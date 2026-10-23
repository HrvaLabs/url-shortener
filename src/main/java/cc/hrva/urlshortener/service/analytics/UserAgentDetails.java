package cc.hrva.urlshortener.service.analytics;

public record UserAgentDetails(boolean bot, String device, String browser, String os) {
}
