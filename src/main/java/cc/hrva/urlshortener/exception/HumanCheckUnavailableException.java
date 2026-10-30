package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class HumanCheckUnavailableException extends CommonException {

    public HumanCheckUnavailableException(final String message) {
        super(message);
    }

}
