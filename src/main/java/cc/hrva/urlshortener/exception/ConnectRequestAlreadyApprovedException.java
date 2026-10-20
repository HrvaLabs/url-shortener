package cc.hrva.urlshortener.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ConnectRequestAlreadyApprovedException extends CommonException {

    public ConnectRequestAlreadyApprovedException() {
        super("already_approved");
    }

}
