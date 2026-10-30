package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.exception.LastSignInMethodException;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.SignInMethodState;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserSignInMethodRepository;
import cc.hrva.urlshortener.security.OAuthProviders;
import cc.hrva.urlshortener.security.StoredPasswords;
import cc.hrva.urlshortener.service.SettingsService;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignInMethodGuard {

    private static final Set<SignInMethod> PROVIDERS = EnumSet.of(SignInMethod.GOOGLE, SignInMethod.GITHUB);

    private final UserSignInMethodRepository userSignInMethodRepository;
    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final SettingsService settingsService;

    public static boolean isProvider(final SignInMethod method) {
        return PROVIDERS.contains(method);
    }

    public static boolean signsInAlone(final PasskeyCredential passkey) {
        return Boolean.TRUE.equals(passkey.getDiscoverable());
    }

    public WaysToSignIn waysOf(final User lockedUser) {
        final var states = userSignInMethodRepository.findStatesByUserId(lockedUser.getId());
        final var providers = states.stream()
                .filter(state -> isProvider(state.method()) && !state.turnedOff())
                .map(SignInMethodState::method)
                .filter(this::onSiteWide)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(SignInMethod.class)));

        return new WaysToSignIn(hasPassword(lockedUser, states), providers,
                passkeyCredentialRepository.countSignInPasskeysByUserId(lockedUser.getId()));
    }

    public void check(final User lockedUser, final WaysToSignIn before, final WaysToSignIn after) {
        if (!after.any()) {
            log.info("Sign-in method change refused, it would leave no way to sign in user id={}", lockedUser.getId());
            throw new LastSignInMethodException();
        }
        if (Boolean.TRUE.equals(lockedUser.getTwoStepEnabled()) && before.anyFirstStep() && !after.anyFirstStep()) {
            log.info("Sign-in method change refused, two-step needs a first step user id={}", lockedUser.getId());
            throw new LastSignInMethodException();
        }
    }

    public void checkRemoval(final User lockedUser, final PasskeyCredential passkey) {
        final var before = waysOf(lockedUser);
        check(lockedUser, before, signsInAlone(passkey) ? before.withoutOnePasskey() : before);
    }

    public boolean stillOn(final User lockedUser, final SignInMethod method) {
        if (method == SignInMethod.PASSWORD) {
            return hasPassword(lockedUser, userSignInMethodRepository.findStatesByUserId(lockedUser.getId()));
        }
        if (isProvider(method)) {
            return onSiteWide(method) && userSignInMethodRepository.findStatesByUserId(lockedUser.getId()).stream()
                    .noneMatch(state -> state.method() == method && state.turnedOff());
        }

        return true;
    }

    public static boolean hasPassword(final User user, final List<SignInMethodState> states) {
        return StoredPasswords.isSet(user.getPassword())
                && states.stream().noneMatch(state -> state.method() == SignInMethod.PASSWORD && state.turnedOff());
    }

    private boolean onSiteWide(final SignInMethod provider) {
        return OAuthProviders.switchOf(provider).map(settingsService::isOnAndAvailable).orElse(true);
    }

}
