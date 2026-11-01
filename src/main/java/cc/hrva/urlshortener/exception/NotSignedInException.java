package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class NotSignedInException extends NoAuthorizationException {

    public NotSignedInException(final String message) {
        super(message);
    }

}
