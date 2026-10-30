package cc.hrva.urlshortener.service.domain;

import java.util.ArrayList;
import java.util.Optional;

final class Punycode {

    private static final int BASE = 36;
    private static final int T_MIN = 1;
    private static final int T_MAX = 26;
    private static final int SKEW = 38;
    private static final int DAMP = 700;
    private static final int INITIAL_BIAS = 72;
    private static final int INITIAL_N = 0x80;
    private static final int MAX_CODE_POINT = Character.MAX_CODE_POINT;
    private static final char DELIMITER = '-';

    private Punycode() {}

    static Optional<String> decode(final String input) {
        final var delimiter = Math.max(input.lastIndexOf(DELIMITER), 0);
        final var output = new ArrayList<Integer>();
        for (int index = 0; index < delimiter; index++) {
            final var character = input.charAt(index);
            if (character >= INITIAL_N) {
                return Optional.empty();
            }
            output.add((int) character);
        }
        var n = INITIAL_N;
        var i = 0;
        var bias = INITIAL_BIAS;
        var position = delimiter > 0 ? delimiter + 1 : 0;
        while (position < input.length()) {
            final var oldI = i;
            var weight = 1;
            for (int k = BASE; ; k += BASE) {
                if (position >= input.length()) {
                    return Optional.empty();
                }
                final var digit = digitOf(input.charAt(position++));
                if (digit < 0 || digit > (Integer.MAX_VALUE - i) / weight) {
                    return Optional.empty();
                }
                i += digit * weight;
                final var threshold = threshold(k, bias);
                if (digit < threshold) {
                    break;
                }
                if (weight > Integer.MAX_VALUE / (BASE - threshold)) {
                    return Optional.empty();
                }
                weight *= BASE - threshold;
            }
            final var length = output.size() + 1;
            bias = adapt(i - oldI, length, oldI == 0);
            if (i / length > MAX_CODE_POINT - n) {
                return Optional.empty();
            }
            n += i / length;
            i %= length;
            if (n >= Character.MIN_SURROGATE && n <= Character.MAX_SURROGATE) {
                return Optional.empty();
            }
            output.add(i, n);
            i++;
        }
        return Optional.of(new String(output.stream().mapToInt(Integer::intValue).toArray(), 0, output.size()));
    }

    static Optional<String> encode(final String input) {
        final var codePoints = input.codePoints().toArray();
        final var output = new StringBuilder();
        for (final var codePoint : codePoints) {
            if (codePoint < INITIAL_N) {
                output.append((char) codePoint);
            }
        }
        final var basicCount = output.length();
        if (basicCount > 0) {
            output.append(DELIMITER);
        }
        var n = INITIAL_N;
        var delta = 0;
        var bias = INITIAL_BIAS;
        var handled = basicCount;
        while (handled < codePoints.length) {
            var next = Integer.MAX_VALUE;
            for (final var codePoint : codePoints) {
                if (codePoint >= n && codePoint < next) {
                    next = codePoint;
                }
            }
            if (next - n > (Integer.MAX_VALUE - delta) / (handled + 1)) {
                return Optional.empty();
            }
            delta += (next - n) * (handled + 1);
            n = next;
            for (final var codePoint : codePoints) {
                if (codePoint < n) {
                    if (delta == Integer.MAX_VALUE) {
                        return Optional.empty();
                    }
                    delta++;
                }
                if (codePoint == n) {
                    var q = delta;
                    for (int k = BASE; ; k += BASE) {
                        final var threshold = threshold(k, bias);
                        if (q < threshold) {
                            break;
                        }
                        output.append(digitChar(threshold + (q - threshold) % (BASE - threshold)));
                        q = (q - threshold) / (BASE - threshold);
                    }
                    output.append(digitChar(q));
                    bias = adapt(delta, handled + 1, handled == basicCount);
                    delta = 0;
                    handled++;
                }
            }
            delta++;
            n++;
        }
        return Optional.of(output.toString());
    }

    private static int threshold(final int k, final int bias) {
        if (k <= bias) {
            return T_MIN;
        }
        return Math.min(k - bias, T_MAX);
    }

    private static int adapt(final int delta, final int length, final boolean first) {
        var scaled = first ? delta / DAMP : delta / 2;
        scaled += scaled / length;
        var k = 0;
        while (scaled > (BASE - T_MIN) * T_MAX / 2) {
            scaled /= BASE - T_MIN;
            k += BASE;
        }
        return k + (BASE - T_MIN + 1) * scaled / (scaled + SKEW);
    }

    private static int digitOf(final char character) {
        if (character >= '0' && character <= '9') {
            return character - '0' + 26;
        }
        if (character >= 'a' && character <= 'z') {
            return character - 'a';
        }
        if (character >= 'A' && character <= 'Z') {
            return character - 'A';
        }
        return -1;
    }

    private static char digitChar(final int digit) {
        return (char) (digit < 26 ? 'a' + digit : '0' + digit - 26);
    }

}
