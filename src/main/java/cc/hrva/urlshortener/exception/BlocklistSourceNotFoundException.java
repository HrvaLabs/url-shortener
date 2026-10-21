package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class BlocklistSourceNotFoundException extends CommonException {

    public BlocklistSourceNotFoundException(final String message) {
        super(message);
    }

}
