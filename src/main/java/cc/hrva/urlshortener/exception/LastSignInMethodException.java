package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class LastSignInMethodException extends CommonException {

    public static final String KEEP_ONE = "Keep at least one way to sign in. Add a passkey or another method first.";

    public LastSignInMethodException() {
        super(KEEP_ONE);
    }

}
