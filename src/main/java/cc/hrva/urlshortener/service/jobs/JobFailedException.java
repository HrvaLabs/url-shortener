package cc.hrva.urlshortener.service.jobs;

public class JobFailedException extends RuntimeException {

    public JobFailedException(final String reason) {
        super(reason);
    }

}
