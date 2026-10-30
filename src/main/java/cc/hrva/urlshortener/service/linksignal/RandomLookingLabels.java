package cc.hrva.urlshortener.service.linksignal;

import cc.hrva.urlshortener.util.DomainNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class RandomLookingLabels {

    public static final double THRESHOLD = -3.55;

    private static final String TRAINING_LABELS = "moderation/common-domain-labels.txt";
    private static final String COMMENT = "#";
    private static final String PUNYCODE_PREFIX = "xn--";
    private static final Pattern LABEL = Pattern.compile("[a-z0-9-]+");
    private static final int MIN_SCORED_LENGTH = 6;
    private static final double SMOOTHING = 0.1;
    private static final int LETTERS = 26;
    private static final int DIGIT = LETTERS;
    private static final int BOUNDARY = DIGIT + 1;
    private static final int CLASSES = BOUNDARY + 1;

    private final double[][] logProbabilities = new double[CLASSES][CLASSES];

    public RandomLookingLabels() {
        this(trainingLabels());
    }

    RandomLookingLabels(final List<String> trainingLabels) {
        final var counts = new long[CLASSES][CLASSES];
        trainingLabels.forEach(label -> forEachTransition(label, (from, to) -> counts[from][to]++));
        for (int from = 0; from < CLASSES; from++) {
            var total = 0L;
            for (final var count : counts[from]) {
                total += count;
            }
            for (int to = 0; to < CLASSES; to++) {
                logProbabilities[from][to] = Math.log((counts[from][to] + SMOOTHING) / (total + SMOOTHING * CLASSES));
            }
        }
    }

    public boolean looksRandom(final String host) {
        return labelsToScore(host).stream()
                .map(this::score)
                .anyMatch(score -> score.isPresent() && score.getAsDouble() < THRESHOLD);
    }

    public OptionalDouble score(final String label) {
        if (!isScored(label)) {
            return OptionalDouble.empty();
        }
        final var sum = new double[1];
        final var transitions = new int[1];
        forEachTransition(label, (from, to) -> {
            sum[0] += logProbabilities[from][to];
            transitions[0]++;
        });
        return OptionalDouble.of(sum[0] / transitions[0]);
    }

    private static List<String> labelsToScore(final String host) {
        final var normalized = Strings.CS.removeEnd(StringUtils.lowerCase(StringUtils.strip(host), Locale.ROOT), ".");
        if (StringUtils.isEmpty(normalized) || DomainNames.isIpLiteral(normalized)) {
            return List.of();
        }
        final var labels = new ArrayList<String>();
        final var registrable = DomainNames.registrableDomain(normalized);
        labels.add(StringUtils.substringBefore(registrable, "."));
        if (normalized.length() > registrable.length()) {
            labels.add(StringUtils.substringBefore(normalized, "."));
        }
        return labels;
    }

    private static boolean isScored(final String label) {
        return label != null && label.length() >= MIN_SCORED_LENGTH && LABEL.matcher(label).matches() && !label.startsWith(PUNYCODE_PREFIX);
    }

    private static void forEachTransition(final String label, final Transition transition) {
        var from = BOUNDARY;
        for (final var character : label.toCharArray()) {
            if (character == '-') {
                transition.accept(from, BOUNDARY);
                from = BOUNDARY;
                continue;
            }
            final var to = Character.isDigit(character) ? DIGIT : character - 'a';
            transition.accept(from, to);
            from = to;
        }
        transition.accept(from, BOUNDARY);
    }

    private static List<String> trainingLabels() {
        try {
            return new ClassPathResource(TRAINING_LABELS).getContentAsString(StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith(COMMENT))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .filter(line -> LABEL.matcher(line).matches())
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException("Could not read " + TRAINING_LABELS, exception);
        }
    }

    @FunctionalInterface
    private interface Transition {

        void accept(int from, int to);

    }

}
