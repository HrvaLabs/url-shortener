package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class EmailNotVerifiedException extends CommonException {

    public EmailNotVerifiedException() {
        super("Please verify your email before logging in.");
    }

    public EmailNotVerifiedException(final String message) {
        super(message);
    }

}
