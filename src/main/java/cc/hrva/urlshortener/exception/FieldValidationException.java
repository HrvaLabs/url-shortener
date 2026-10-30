package cc.hrva.urlshortener.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;

@Getter
public class FieldValidationException extends CommonException {

    private final transient Map<String, String> fieldErrors;

    public FieldValidationException(final Map<String, String> fieldErrors) {
        super("Invalid field values");
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public FieldValidationException(final String field, final String text) {
        this(Map.of(field, text));
    }

}
