package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class EmailLogNotFoundException extends CommonException {

    public EmailLogNotFoundException(final String message) {
        super(message);
    }

}
