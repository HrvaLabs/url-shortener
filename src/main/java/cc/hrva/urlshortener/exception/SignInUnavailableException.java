package cc.hrva.urlshortener.exception;

import java.time.Duration;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@Getter
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class SignInUnavailableException extends CommonException {

    private final transient Duration retryAfter;

    public SignInUnavailableException(final String message) {
        super(message);
        this.retryAfter = null;
    }

    public SignInUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
        this.retryAfter = null;
    }

    public SignInUnavailableException(final String message, final Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

}
