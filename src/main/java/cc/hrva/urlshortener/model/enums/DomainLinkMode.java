package cc.hrva.urlshortener.model.enums;

import cc.hrva.urlshortener.exception.ApiException;
import java.util.Arrays;

public enum DomainLinkMode {

    MOVE,
    DEACTIVATE;

    private static final String INVALID = "links must be MOVE or DEACTIVATE";

    public static DomainLinkMode fromParameter(final String links) {
        if (links == null) {
            return DEACTIVATE;
        }
        return Arrays.stream(values())
                .filter(mode -> mode.name().equals(links))
                .findFirst()
                .orElseThrow(() -> new ApiException(INVALID));
    }

}
