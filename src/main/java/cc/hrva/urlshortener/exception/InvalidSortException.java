package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidSortException extends CommonException {

    public InvalidSortException(final String message) {
        super(message);
    }

}
