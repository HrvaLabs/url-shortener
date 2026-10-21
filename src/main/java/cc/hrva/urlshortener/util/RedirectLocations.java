package cc.hrva.urlshortener.util;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

public final class RedirectLocations {

    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final String AUTHORITY_START = "//";
    private static final String AUTHORITY_END = "/?#";
    private static final String ALWAYS_ENCODED = "\"<>{}|^`";
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private RedirectLocations() {}

    public static String normalized(final String location) {
        final var slashed = location.replace('\\', '/');
        final var scheme = SCHEME.matcher(slashed);
        final var schemeEnd = scheme.find() ? scheme.end() : 0;
        final var rest = slashed.substring(schemeEnd);
        if (!rest.startsWith(AUTHORITY_START)) {
            return slashed.substring(0, schemeEnd) + encoded(rest);
        }

        final var afterSlashes = rest.substring(AUTHORITY_START.length());
        final var authorityEnd = StringUtils.indexOfAny(afterSlashes, AUTHORITY_END);
        final var authority = authorityEnd < 0 ? afterSlashes : afterSlashes.substring(0, authorityEnd);
        final var tail = authorityEnd < 0 ? "" : afterSlashes.substring(authorityEnd);
        return slashed.substring(0, schemeEnd) + AUTHORITY_START + asciiAuthority(authority) + encoded(tail);
    }

    private static String asciiAuthority(final String authority) {
        final var hostStart = authority.lastIndexOf('@') + 1;
        final var userInfo = encoded(authority.substring(0, hostStart));
        final var hostAndPort = authority.substring(hostStart);
        if (hostAndPort.startsWith("[")) {
            return userInfo + hostAndPort;
        }
        final var portStart = hostAndPort.indexOf(':');
        final var host = portStart < 0 ? hostAndPort : hostAndPort.substring(0, portStart);
        final var port = portStart < 0 ? "" : hostAndPort.substring(portStart);
        return userInfo + IDN.toASCII(host) + port;
    }

    private static String encoded(final String part) {
        final var bytes = part.getBytes(StandardCharsets.UTF_8);
        final var encoded = new StringBuilder(bytes.length);
        for (int index = 0; index < bytes.length; index++) {
            if (needsEncoding(bytes, index)) {
                encoded.append('%').append(HEX.toHexDigits(bytes[index]));
            } else {
                encoded.append((char) bytes[index]);
            }
        }
        return encoded.toString();
    }

    private static boolean needsEncoding(final byte[] bytes, final int index) {
        final var character = bytes[index] & 0xFF;
        if (character == '%') {
            return index + 2 >= bytes.length || !HexFormat.isHexDigit(bytes[index + 1]) || !HexFormat.isHexDigit(bytes[index + 2]);
        }
        return character <= ' ' || character >= 0x7F || ALWAYS_ENCODED.indexOf(character) >= 0;
    }

}
