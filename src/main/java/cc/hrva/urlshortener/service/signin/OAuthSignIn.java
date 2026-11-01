package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.model.enums.SignInMethod;

public record OAuthSignIn(Long userId, SignInMethod method) {
}
