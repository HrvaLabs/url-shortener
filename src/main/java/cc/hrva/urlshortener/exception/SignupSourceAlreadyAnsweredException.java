package cc.hrva.urlshortener.exception;

public class SignupSourceAlreadyAnsweredException extends CommonException {

    public SignupSourceAlreadyAnsweredException() {
        super("Already answered.");
    }

}
