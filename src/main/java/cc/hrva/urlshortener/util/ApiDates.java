package cc.hrva.urlshortener.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class ApiDates {

    private ApiDates() {}

    public static String utc(final LocalDateTime storedTime) {
        return storedTime == null ? null : DateTimeFormatter.ISO_INSTANT.format(storedTime.toInstant(ZoneOffset.UTC));
    }

}
