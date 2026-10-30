package cc.hrva.urlshortener.service.settings;

import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class SettingValues {

    private static final String FIELD = "value";
    private static final String SWITCH_TEXT = "Choose on or off.";
    private static final String NUMBER_TEXT = "Use a number from %,d to %,d.";
    private static final String TEXT_TEXT = "Use %d to %d characters.";
    private static final String ONE_ADDRESS_TEXT = "Enter one email address.";
    private static final String ADDRESSES_TEXT = "Enter up to %d email addresses.";
    private static final String BAD_ADDRESS_TEXT = "%s isn't an email address.";
    private static final int MAX_ADDRESS_LENGTH = 254;
    private static final Pattern LOCAL_PART = Pattern.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$");
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
            case TEXT -> textOf(key, value);
            case ADDRESSES -> addressesOf(key, value);
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

    private static String textOf(final SettingKey key, final JsonNode value) {
        final var text = value == null || !value.isString() ? null : value.stringValue().strip();
        if (text == null || text.isEmpty() || text.codePointCount(0, text.length()) < key.getMinLength()
                || text.codePointCount(0, text.length()) > key.getMaxLength()) {
            throw new FieldValidationException(FIELD, String.format(Locale.ENGLISH, TEXT_TEXT, key.getMinLength(), key.getMaxLength()));
        }
        return text;
    }

    private static List<String> addressesOf(final SettingKey key, final JsonNode value) {
        final var single = key.getMaxItems() == 1;
        final var countText = single ? ONE_ADDRESS_TEXT : String.format(Locale.ENGLISH, ADDRESSES_TEXT, key.getMaxItems());
        if (value == null || !value.isArray() || value.size() < key.getMinItems() || value.size() > key.getMaxItems()) {
            throw new FieldValidationException(FIELD, countText);
        }
        final var addresses = new ArrayList<String>();
        for (final var entry : value) {
            final var address = entry.isString() ? entry.stringValue().strip() : entry.toString();
            if (!entry.isString() || !isEmailAddress(address)) {
                throw new FieldValidationException(FIELD, single ? countText : String.format(Locale.ENGLISH, BAD_ADDRESS_TEXT, address));
            }
            addresses.add(address);
        }
        return List.copyOf(addresses);
    }

    private static boolean isEmailAddress(final String address) {
        final var at = address.lastIndexOf('@');
        if (at <= 0 || address.length() > MAX_ADDRESS_LENGTH) {
            return false;
        }
        final var domain = address.substring(at + 1);
        return LOCAL_PART.matcher(address.substring(0, at)).matches()
                && !domain.endsWith(".")
                && DomainNames.HOST_NAME.matcher(domain).matches()
                && !StringUtils.isNumeric(StringUtils.substringAfterLast(domain, "."));
    }

    private static FieldValidationException outOfRange(final SettingKey key) {
        return new FieldValidationException(FIELD, String.format(Locale.ENGLISH, NUMBER_TEXT, key.getMin(), key.getMax()));
    }

}
