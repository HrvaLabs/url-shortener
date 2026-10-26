package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class SignInUnavailableException extends CommonException {

    public SignInUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
