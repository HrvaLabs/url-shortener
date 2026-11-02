package cc.hrva.urlshortener.exception;

public class LinkExpiredException extends CommonException {

    public LinkExpiredException(final String message) {
        super(message);
    }

}
