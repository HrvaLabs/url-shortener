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

class CloudflareCreateCallersTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final String CLIENT = "CloudflareClient.java";
    private static final String PROVISIONER = "CustomDomainProvisioner.java";
    private static final String CLIENT_PACKAGE = "package cc.hrva.urlshortener.client;";
    private static final Pattern CLIENT_REFERENCE = Pattern.compile("\\bCloudflareClient\\b");
    private static final Pattern CREATE_CALL = Pattern.compile("\\.\\s*create\\s*\\(|::\\s*create\\b");

    @Test
    void onlyTheProvisionerReferencesCreate() {
        final var callers = sources()
                .filter(CloudflareCreateCallersTest::usesTheClient)
                .filter(source -> !source.name().equals(CLIENT))
                .toList();

        assertThat(callers).extracting(Source::name).contains(PROVISIONER, "CloudflareTokenCheck.java");
        assertThat(callers).filteredOn(source -> CREATE_CALL.matcher(source.text()).find())
                .extracting(Source::name)
                .allMatch(PROVISIONER::equals);
    }

    @Test
    void theGuardSeesACreateCallUnderAnyName() {
        assertThat(List.of("cloudflareClient.create(hostname)", "client.create(hostname)", "hostnames.create (hostname)",
                "client\n        .create(hostname)", "Optional.of(hostname).map(client::create)", "map(cloudflare :: create)"))
                .allMatch(text -> CREATE_CALL.matcher(text).find());
        assertThat(List.of("client.findByHostname(hostname)", "client.createdAt()", "recordCreated(id)", "client.created(hostname)"))
                .noneMatch(text -> CREATE_CALL.matcher(text).find());
    }

    @Test
    void aClassInTheClientPackageUsesTheClientWithoutAnImport() {
        assertThat(usesTheClient(new Source("Sibling.java", CLIENT_PACKAGE + "\nclass Sibling { private final CloudflareClient hostnames; }"))).isTrue();
        assertThat(usesTheClient(new Source("Other.java", "package cc.hrva.urlshortener.service;\nclass Other { }"))).isFalse();
    }

    private static boolean usesTheClient(final Source source) {
        return CLIENT_REFERENCE.matcher(source.text()).find();
    }

    private static Stream<Source> sources() {
        assertThat(MAIN_SOURCES).isDirectory();
        try (var paths = Files.walk(MAIN_SOURCES)) {
            return paths.filter(path -> path.toString().endsWith(".java")).map(CloudflareCreateCallersTest::read).toList().stream();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Source read(final Path path) {
        try {
            return new Source(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Source(String name, String text) {
    }

}
