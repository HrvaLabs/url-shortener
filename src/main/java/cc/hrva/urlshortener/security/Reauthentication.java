package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.ReauthenticationRequiredException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.validator.AuthValidator;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Reauthentication {

    private static final String LOCAL_PROVIDER = "local";

    private final RecentSignIn recentSignIn;
    private final AuthValidator authValidator;

    public void confirm(final User user, final String password) {
        if (isLocalAccount(user)) {
            if (StringUtils.isBlank(password)) {
                throw new ApiException("Enter your current password.");
            }
            authValidator.passwordMatchesCurrentPassword(user, password);
            return;
        }

        if (!recentSignIn.isRecent(SecurityContextHolder.getContext().getAuthentication())) {
            throw new ReauthenticationRequiredException("For your security, sign in again to change your passkeys and security keys.");
        }
    }

    private static boolean isLocalAccount(final User user) {
        return user.getAuthProvider() == null || LOCAL_PROVIDER.equals(user.getAuthProvider());
    }

}
