package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.ReauthenticationRequiredException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.validator.AuthValidator;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Reauthentication {

    private static final String LOCAL_PROVIDER = "local";
    private static final String ATTEMPTS_PREFIX = "reauth:";
    private static final String TOO_MANY_WRONG_PASSWORDS = "Too many wrong passwords. Try again in 24 hours.";
    private static final String SIGN_IN_AGAIN_FOR_PASSKEYS = "For your security, sign in again to change your passkeys and security keys.";
    private static final String SIGN_IN_AGAIN_TO_DELETE = "For your security, sign in again to delete your account.";

    private final RecentSignIn recentSignIn;
    private final AuthValidator authValidator;
    private final LoginAttemptService loginAttemptService;

    public void confirm(final User user, final String password) {
        confirm(user, password, SIGN_IN_AGAIN_FOR_PASSKEYS);
    }

    public void confirmAccountDeletion(final User user, final String password) {
        confirm(user, password, SIGN_IN_AGAIN_TO_DELETE);
    }

    public void forgetWrongPasswords(final User user) {
        loginAttemptService.loginSucceeded(attemptsKeyOf(user));
    }

    public void confirmPassword(final User user, final String password) {
        if (StringUtils.isBlank(password)) {
            throw new ApiException("Enter your current password.");
        }

        final var attemptsKey = attemptsKeyOf(user);
        if (loginAttemptService.isBlocked(attemptsKey)) {
            throw new NoAuthorizationException(TOO_MANY_WRONG_PASSWORDS);
        }
        try {
            authValidator.passwordMatchesCurrentPassword(user, password);
        } catch (final NoAuthorizationException exception) {
            loginAttemptService.loginFailed(attemptsKey);
            throw exception;
        }
        loginAttemptService.loginSucceeded(attemptsKey);
    }

    private void confirm(final User user, final String password, final String signInAgain) {
        if (isLocalAccount(user)) {
            confirmPassword(user, password);
            return;
        }

        if (!recentSignIn.isRecent(SecurityContextHolder.getContext().getAuthentication())) {
            throw new ReauthenticationRequiredException(signInAgain);
        }
    }

    private static String attemptsKeyOf(final User user) {
        return ATTEMPTS_PREFIX + user.getId();
    }

    private static boolean isLocalAccount(final User user) {
        return user.getAuthProvider() == null || LOCAL_PROVIDER.equals(user.getAuthProvider());
    }

}
