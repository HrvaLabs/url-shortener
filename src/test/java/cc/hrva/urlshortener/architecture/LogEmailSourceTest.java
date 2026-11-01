package cc.hrva.urlshortener.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogEmailSourceTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(?:trace|debug|info|warn|error)\\(((?:[^;\"]|\"(?:[^\"\\\\]|\\\\.)*\")*);");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern MASKED = Pattern.compile("EmailAddresses\\s*\\.\\s*masked(?:In)?\\s*\\((?:[^()]|\\([^()]*\\))*\\)");
    private static final Pattern EMAIL_ARGUMENT = Pattern.compile(
            "\\bget(?:Email|EmailAddress|Recipient)\\s*\\(|\\.(?:email|emailAddress|recipient)\\s*\\(|(?<![.\\w])(?:email|userEmail|toEmail|emailAddress|recipient|recipients)\\b(?!\\s*[.(])");

    @Test
    void noLogCallPrintsAFullEmailAddress() {
        final var offenders = sources().flatMap(LogEmailSourceTest::unmaskedEmailArguments).toList();

        assertThat(offenders).isEmpty();
    }

    @Test
    void theGuardSeesEveryShapeOfAnEmailArgument() {
        assertThat(List.of(
                "log.info(\"Registered email={}\", user.getEmail());",
                "log.info(\"Registered email={}\", email);",
                "log.info(\"Reset for {}\", dto.getEmail());",
                "log.warn(\"Sent to {}\", recipients);",
                "log.warn(\"Sent to {}\", message.getRecipient());",
                "log.info(\"Mixed {} {}\", EmailAddresses.masked(email), user.getEmail());",
                "log.info(\"Split\",\n        dto.getEmail());"))
                .allMatch(statement -> hasUnmaskedEmail(statement));
        assertThat(List.of(
                "log.info(\"Registered email={}\", EmailAddresses.masked(user.getEmail()));",
                "log.info(\"Audit {}\", EmailAddresses.maskedIn(performedBy));",
                "log.info(\"Login for email={} user id={}\", EmailAddresses.masked(email), user.getId());",
                "log.info(\"The email could not be sent user id={}\", user.getId());",
                "log.warn(\"Sent {} to {} recipient(s)\", type, email.getReceivers().length, emails);"))
                .noneMatch(statement -> hasUnmaskedEmail(statement));
    }

    private static boolean hasUnmaskedEmail(final String statement) {
        final var call = LOG_CALL.matcher(statement);
        return call.find() && EMAIL_ARGUMENT.matcher(argumentsOf(call.group(1))).find();
    }

    private static String argumentsOf(final String callBody) {
        final var withoutMasked = MASKED.matcher(callBody).replaceAll("");
        return STRING_LITERAL.matcher(withoutMasked).replaceAll("\"\"");
    }

    private static Stream<String> unmaskedEmailArguments(final Source source) {
        return LOG_CALL.matcher(source.text()).results()
                .filter(call -> EMAIL_ARGUMENT.matcher(argumentsOf(call.group(1))).find())
                .map(call -> source.path() + ":" + lineOf(source.text(), call.start()) + " " + call.group().strip());
    }

    private static long lineOf(final String text, final int offset) {
        return text.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
    }

    private static Stream<Source> sources() {
        assertThat(MAIN_SOURCES).isDirectory();
        try (var paths = Files.walk(MAIN_SOURCES)) {
            return paths.filter(path -> path.toString().endsWith(".java")).map(LogEmailSourceTest::read).toList().stream();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Source read(final Path path) {
        try {
            return new Source(path, Files.readString(path, StandardCharsets.UTF_8));
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Source(Path path, String text) {
    }

}
