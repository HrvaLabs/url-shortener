package cc.hrva.urlshortener.exception;

import cc.hrva.urlshortener.dto.MissingRequirement;
import java.util.List;
import lombok.Getter;

@Getter
public class DestinationChangeRefusedException extends CommonException {

    private final transient List<MissingRequirement> missing;

    public DestinationChangeRefusedException(final String message, final List<MissingRequirement> missing) {
        super(message);
        this.missing = missing;
    }

}
