package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ConnectedAppNotFoundException extends CommonException {

    public ConnectedAppNotFoundException() {
        super("Connected app not found");
    }

}
