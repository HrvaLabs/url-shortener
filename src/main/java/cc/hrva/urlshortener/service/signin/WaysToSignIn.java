package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.util.EnumSet;
import java.util.Set;

public record WaysToSignIn(boolean password, Set<SignInMethod> providers, long passkeys) {

    public WaysToSignIn {
        providers = Set.copyOf(providers);
    }

    public boolean any() {
        return anyFirstStep() || passkeys > 0;
    }

    public boolean anyFirstStep() {
        return password || !providers.isEmpty();
    }

    public WaysToSignIn withoutPassword() {
        return new WaysToSignIn(false, providers, passkeys);
    }

    public WaysToSignIn withoutProvider(final SignInMethod provider) {
        final var remaining = EnumSet.noneOf(SignInMethod.class);
        remaining.addAll(providers);
        remaining.remove(provider);

        return new WaysToSignIn(password, remaining, passkeys);
    }

    public WaysToSignIn withoutOnePasskey() {
        return new WaysToSignIn(password, providers, Math.max(0, passkeys - 1));
    }

}
