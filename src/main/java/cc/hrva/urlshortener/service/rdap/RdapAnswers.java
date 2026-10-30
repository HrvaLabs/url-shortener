package cc.hrva.urlshortener.service.rdap;

import cc.hrva.urlshortener.util.EmailAddresses;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.JsonNode;

public final class RdapAnswers {

    private static final String REGISTRATION = "registration";
    private static final String REGISTRAR = "registrar";
    private static final String ABUSE = "abuse";
    private static final String ENTITIES = "entities";
    private static final String FULL_NAME = "fn";
    private static final String EMAIL = "email";
    private static final int ISO_DATE_LENGTH = 10;
    private static final int MAX_NAME_LENGTH = 255;
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    private RdapAnswers() {
    }

    public static DomainRecord found(final String domain, final JsonNode answer) {
        final var registrar = entitiesWithRole(answer, REGISTRAR).findFirst();
        final var abuseEmail = registrar.flatMap(entity -> entitiesWithRole(entity, ABUSE).flatMap(abuse -> vcardText(abuse, EMAIL).stream()).findFirst())
                .or(() -> entitiesWithRole(answer, ABUSE).flatMap(abuse -> vcardText(abuse, EMAIL).stream()).findFirst())
                .filter(EmailAddresses::isValid);
        final var statuses = answer.path("status").valueStream()
                .flatMap(status -> status.stringValueOpt().stream())
                .map(status -> status.strip().toLowerCase(Locale.ROOT))
                .filter(StringUtils::isNotEmpty)
                .toList();
        return new DomainRecord(domain, DomainRecord.Status.FOUND, registeredOn(answer).orElse(null),
                registrar.flatMap(entity -> vcardText(entity, FULL_NAME)).flatMap(RdapAnswers::cleanName).orElse(null), abuseEmail.orElse(null), statuses);
    }

    public static Optional<LocalDate> registeredOn(final JsonNode answer) {
        return answer.path("events").valueStream()
                .filter(event -> event.path("eventAction").stringValueOpt().filter(REGISTRATION::equals).isPresent())
                .findFirst()
                .flatMap(event -> event.path("eventDate").stringValueOpt())
                .flatMap(RdapAnswers::utcDateOf);
    }

    private static Stream<JsonNode> entitiesWithRole(final JsonNode parent, final String role) {
        return parent.path(ENTITIES).valueStream()
                .filter(entity -> entity.path("roles").valueStream()
                        .anyMatch(candidate -> candidate.stringValueOpt().filter(role::equalsIgnoreCase).isPresent()));
    }

    private static Optional<String> vcardText(final JsonNode entity, final String property) {
        return entity.path("vcardArray").path(1).valueStream()
                .filter(field -> field.path(0).stringValueOpt().filter(property::equalsIgnoreCase).isPresent())
                .flatMap(field -> field.path(3).stringValueOpt().stream())
                .map(String::strip)
                .filter(StringUtils::isNotEmpty)
                .findFirst();
    }

    private static Optional<String> cleanName(final String name) {
        final var visible = StringUtils.normalizeSpace(INVISIBLE.matcher(name).replaceAll(" "));
        if (visible.isEmpty()) {
            return Optional.empty();
        }
        if (visible.length() <= MAX_NAME_LENGTH) {
            return Optional.of(visible);
        }
        final var end = Character.isHighSurrogate(visible.charAt(MAX_NAME_LENGTH - 1)) ? MAX_NAME_LENGTH - 1 : MAX_NAME_LENGTH;
        return Optional.of(visible.substring(0, end).strip());
    }

    private static Optional<LocalDate> utcDateOf(final String timestamp) {
        try {
            return Optional.of(OffsetDateTime.parse(timestamp).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate());
        } catch (final DateTimeParseException exception) {
            return timestamp.length() >= ISO_DATE_LENGTH ? isoDateOf(timestamp.substring(0, ISO_DATE_LENGTH)) : Optional.empty();
        }
    }

    private static Optional<LocalDate> isoDateOf(final String value) {
        try {
            return Optional.of(LocalDate.parse(value));
        } catch (final DateTimeParseException exception) {
            return Optional.empty();
        }
    }

}
