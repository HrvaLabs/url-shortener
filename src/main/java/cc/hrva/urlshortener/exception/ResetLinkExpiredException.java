package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.GONE)
public class ResetLinkExpiredException extends CommonException {

    public static final String RESET_LINK_EXPIRED = "This reset link has expired or was already used. Ask for a new one.";

    public ResetLinkExpiredException() {
        super(RESET_LINK_EXPIRED);
    }

}
