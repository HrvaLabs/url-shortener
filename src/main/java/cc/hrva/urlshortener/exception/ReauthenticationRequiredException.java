package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class ReauthenticationRequiredException extends CommonException {

    public ReauthenticationRequiredException(final String message) {
        super(message);
    }

}
