package cc.hrva.urlshortener.service.twostep;

import cc.hrva.urlshortener.model.enums.SignInMethod;

public record PendingLogin(Long userId, boolean rememberMe, SignInMethod method) {
}
