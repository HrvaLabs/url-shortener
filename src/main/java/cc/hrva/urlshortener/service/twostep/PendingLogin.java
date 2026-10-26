package cc.hrva.urlshortener.service.twostep;

public record PendingLogin(Long userId, boolean rememberMe) {
}
