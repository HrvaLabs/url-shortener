package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.GONE)
public class ConnectRequestExpiredException extends CommonException {

    public ConnectRequestExpiredException() {
        super("expired");
    }

}
