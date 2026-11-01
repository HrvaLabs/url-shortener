package cc.hrva.urlshortener.exception;

import java.time.Duration;
import lombok.Getter;

@Getter
public class ServiceBusyException extends CommonException {

    private final transient Duration retryAfter;

    public ServiceBusyException(final String message, final Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

}
