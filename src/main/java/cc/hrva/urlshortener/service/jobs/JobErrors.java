package cc.hrva.urlshortener.service.jobs;

import cc.hrva.urlshortener.util.EmailAddresses;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

final class JobErrors {

    static final int MAX_LENGTH = 500;
    private static final Pattern ADDRESS = Pattern.compile("\\b[a-z][a-z0-9+.-]*://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("\\bbearer\\s+[^\\s\"',;]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "\\b(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|key|authorization)([\"']?\\s*[=:]\\s*[\"']?)[^\\s\"'&,;}]+",
            Pattern.CASE_INSENSITIVE);
    private static final String HIDDEN_ADDRESS = "[url]";
    private static final String HIDDEN_BEARER = "Bearer [hidden]";
    private static final String HIDDEN_VALUE = "$1$2[hidden]";

    private JobErrors() {
    }

    static String describe(final Throwable throwable) {
        final var message = StringUtils.normalizeSpace(throwable.getMessage());
        final var text = StringUtils.isBlank(message) ? throwable.getClass().getName() : throwable.getClass().getName() + ": " + message;
        final var withoutAddresses = ADDRESS.matcher(text).replaceAll(HIDDEN_ADDRESS);
        final var hidden = SECRET_VALUE.matcher(BEARER.matcher(withoutAddresses).replaceAll(HIDDEN_BEARER)).replaceAll(HIDDEN_VALUE);
        return StringUtils.abbreviate(EmailAddresses.maskedIn(hidden), MAX_LENGTH);
    }

}
