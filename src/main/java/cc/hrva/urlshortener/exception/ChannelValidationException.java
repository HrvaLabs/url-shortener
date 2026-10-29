package cc.hrva.urlshortener.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;

@Getter
public class ChannelValidationException extends CommonException {

    private final transient Map<String, String> fieldErrors;

    public ChannelValidationException(final Map<String, String> fieldErrors) {
        super("Invalid channel settings");
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

}
