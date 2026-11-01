package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.util.ApiDates;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdScalarDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdScalarSerializer;

final class UtcDateTimes {

    private UtcDateTimes() {}

    static SimpleModule module() {
        return new SimpleModule("UtcDateTimes")
                .addSerializer(LocalDateTime.class, new Writer())
                .addDeserializer(LocalDateTime.class, new Reader());
    }

    static final class Writer extends StdScalarSerializer<LocalDateTime> {

        Writer() {
            super(LocalDateTime.class);
        }

        @Override
        public void serialize(final LocalDateTime value, final JsonGenerator generator, final SerializationContext context) {
            generator.writeString(ApiDates.utc(value));
        }

    }

    static final class Reader extends StdScalarDeserializer<LocalDateTime> {

        Reader() {
            super(LocalDateTime.class);
        }

        @Override
        public LocalDateTime deserialize(final JsonParser parser, final DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (LocalDateTime) context.handleUnexpectedToken(LocalDateTime.class, parser);
            }
            final var text = parser.getString().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return hasOffset(text)
                        ? OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
                        : LocalDateTime.parse(text);
            } catch (final DateTimeParseException exception) {
                return (LocalDateTime) context.handleWeirdStringValue(LocalDateTime.class, text, "Use ISO 8601, like 2026-12-31T23:59:59Z");
            }
        }

        private static boolean hasOffset(final String text) {
            final var time = text.indexOf('T');
            return time > 0 && (text.endsWith("Z") || text.endsWith("z") || text.indexOf('+', time) > 0 || text.indexOf('-', time) > 0);
        }

    }

}
