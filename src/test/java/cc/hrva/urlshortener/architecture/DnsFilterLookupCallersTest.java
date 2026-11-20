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

class DnsFilterLookupCallersTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final String LOOKUP_PACKAGE = "/service/dnsfilter/";
    private static final List<String> ALLOWED_OUTSIDE_THE_PACKAGE = List.of("AdminModerationController.java", "ModerationTools.java", "OwnHostsCheck.java");
    private static final List<String> NEVER = List.of("DefaultClickCheckService.java", "DefaultUrlService.java");
    private static final Pattern SERVICE_REFERENCE = Pattern.compile("\\bDnsFilterService\\b");

    @Test
    void onlyTheCheckTheAdminViewsAndTheOwnHostCheckLookUp() {
        final var callers = sources().filter(source -> SERVICE_REFERENCE.matcher(source.text()).find()).toList();

        assertThat(callers).extracting(Source::name).contains("DefaultDnsFilterCheckService.java");
        assertThat(callers).filteredOn(source -> !source.path().contains(LOOKUP_PACKAGE))
                .extracting(Source::name)
                .allMatch(ALLOWED_OUTSIDE_THE_PACKAGE::contains);
        assertThat(callers).extracting(Source::name).doesNotContainAnyElementsOf(NEVER);
    }

    @Test
    void theGuardMatchesTheLookupServiceButNotTheCheckService() {
        assertThat(SERVICE_REFERENCE.matcher("private final DnsFilterService dnsFilterService;").find()).isTrue();
        assertThat(SERVICE_REFERENCE.matcher("private final DnsFilterCheckService dnsFilterCheckService;").find()).isFalse();
    }

    private static Stream<Source> sources() {
        assertThat(MAIN_SOURCES).isDirectory();
        try (var paths = Files.walk(MAIN_SOURCES)) {
            return paths.filter(path -> path.toString().endsWith(".java")).map(DnsFilterLookupCallersTest::read).toList().stream();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Source read(final Path path) {
        try {
            return new Source(path.toString().replace('\\', '/'), path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Source(String path, String name, String text) {

    }

}
