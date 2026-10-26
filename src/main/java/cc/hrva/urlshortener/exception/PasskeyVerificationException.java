package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class PasskeyVerificationException extends CommonException {

    public PasskeyVerificationException(final String message) {
        super(message);
    }

}
