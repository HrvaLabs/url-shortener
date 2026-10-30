package cc.hrva.urlshortener.exception;

public class CustomDomainCapacityException extends CustomDomainConflictException {

    public CustomDomainCapacityException(final String message) {
        super(message);
    }

}
