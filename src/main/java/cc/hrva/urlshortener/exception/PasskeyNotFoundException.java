package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class PasskeyNotFoundException extends CommonException {

    public PasskeyNotFoundException(final String message) {
        super(message);
    }

}
