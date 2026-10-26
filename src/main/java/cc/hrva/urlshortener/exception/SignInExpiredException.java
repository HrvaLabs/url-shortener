package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.GONE)
public class SignInExpiredException extends CommonException {

    public SignInExpiredException() {
        super("Your sign-in expired. Sign in again.");
    }

}
