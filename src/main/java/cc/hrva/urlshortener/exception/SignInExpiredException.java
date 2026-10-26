package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.GONE)
public class SignInExpiredException extends CommonException {

    public static final String SIGN_IN_EXPIRED = "Your sign-in expired. Sign in again.";

    public SignInExpiredException() {
        super(SIGN_IN_EXPIRED);
    }

    public SignInExpiredException(final String message) {
        super(message);
    }

}
