package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class SafeBrowsingUnavailableException extends CommonException {

    public SafeBrowsingUnavailableException(final String message) {
        super(message);
    }

}
