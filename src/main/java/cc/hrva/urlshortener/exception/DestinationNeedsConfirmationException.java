package cc.hrva.urlshortener.exception;

import cc.hrva.urlshortener.dto.LinkVerdict;
import lombok.Getter;

@Getter
public class DestinationNeedsConfirmationException extends CommonException {

    private final transient LinkVerdict verdict;

    public DestinationNeedsConfirmationException(final String message, final LinkVerdict verdict) {
        super(message);
        this.verdict = verdict;
    }

}
