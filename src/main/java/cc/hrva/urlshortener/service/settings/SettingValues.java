package cc.hrva.urlshortener.service.settings;

import cc.hrva.urlshortener.exception.FieldValidationException;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class SettingValues {

    private static final String FIELD = "value";
    private static final String SWITCH_TEXT = "Choose on or off.";
    private static final String NUMBER_TEXT = "Use a number from %,d to %,d.";
    private static final String ON = "On";
    private static final String OFF = "Off";
    private static final String LIST_SEPARATOR = ", ";
    private static final JsonMapper JSON = JsonMapper.shared();

    private SettingValues() {
    }

    public static Object parse(final SettingKey key, final JsonNode value) {
        final var absent = value == null || value.isMissingNode() || value.isNull();
        if (absent && key.isNullable()) {
            return null;
        }
        return switch (key.getType()) {
            case SWITCH -> switchOf(value);
            case NUMBER -> numberOf(key, value);
            case TEXT, ADDRESSES -> throw new IllegalStateException("No setting of type " + key.getType() + " can be changed yet");
        };
    }

    public static Object fromStored(final SettingKey key, final String stored) {
        if (stored == null) {
            if (key.isNullable()) {
                return null;
            }
            throw new IllegalArgumentException("No value stored");
        }
        try {
            return parse(key, JSON.readTree(stored));
        } catch (final JacksonException | FieldValidationException exception) {
            throw new IllegalArgumentException("The stored value doesn't fit", exception);
        }
    }

    public static String stored(final Object value) {
        return value == null ? null : JSON.writeValueAsString(value);
    }

    public static String format(final SettingKey key, final Object value) {
        return switch (key.getType()) {
            case SWITCH -> Boolean.TRUE.equals(value) ? ON : OFF;
            case NUMBER -> value == null ? key.getNoneLabel() : String.format(Locale.ENGLISH, "%,d", (Integer) value);
            case TEXT -> String.valueOf(value);
            case ADDRESSES -> value instanceof List<?> addresses ? String.join(LIST_SEPARATOR, addresses.stream().map(String::valueOf).toList()) : "";
        };
    }

    private static boolean switchOf(final JsonNode value) {
        if (value == null || !value.isBoolean()) {
            throw new FieldValidationException(FIELD, SWITCH_TEXT);
        }
        return value.booleanValue();
    }

    private static int numberOf(final SettingKey key, final JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw outOfRange(key);
        }
        final var number = value.intValue();
        if (number < key.getMin() || number > key.getMax()) {
            throw outOfRange(key);
        }
        return number;
    }

    private static FieldValidationException outOfRange(final SettingKey key) {
        return new FieldValidationException(FIELD, String.format(Locale.ENGLISH, NUMBER_TEXT, key.getMin(), key.getMax()));
    }

}
