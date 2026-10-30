package cc.hrva.urlshortener.configuration;

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

class SettingsSourceTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Path SETTING_DEFAULTS = MAIN_SOURCES.resolve(Path.of("cc", "hrva", "urlshortener", "service", "settings", "SettingDefaults.java"));
    private static final List<Pattern> MIGRATED_READS = Stream.of(
            "\\bisAnonymousUrlCreationEnabled\\b",
            "\\bisRegistrationEnabled\\b",
            "\\bisSafeBrowsingFailClosed\\b",
            "\\bgetDestinationCheck\\b(?!\\s*\\(\\s*\\)\\s*\\.\\s*(?:getBudget|isCatchUpOnStartup)\\s*\\()",
            "\\bgetWeekly\\b",
            "\\bgetCampaign\\b",
            "\\bgetTrafficAlerts\\b",
            "\\bgetModeration\\b",
            "\\bgetBurst\\b(?!\\s*\\(\\s*\\)\\s*\\.\\s*getWindow\\s*\\()",
            "\\bgetAbuseReports\\b"
    ).map(Pattern::compile).toList();

    @Test
    void onlySettingDefaultsReadsTheMigratedFlags() {
        final var reads = sources()
                .filter(source -> !source.path().equals(SETTING_DEFAULTS))
                .flatMap(SettingsSourceTest::migratedReads)
                .toList();

        assertThat(reads).isEmpty();
    }

    @Test
    void settingDefaultsStillReadsEveryMigratedFlag() {
        final var defaults = read(SETTING_DEFAULTS).text();

        assertThat(MIGRATED_READS).allMatch(pattern -> pattern.matcher(defaults).find());
    }

    @Test
    void theGuardSeesEveryShapeOfARead() {
        assertThat(List.of(
                "appProperties.isAnonymousUrlCreationEnabled()",
                "map(appProperties::isRegistrationEnabled)",
                "if (appProperties.isSafeBrowsingFailClosed())",
                "appProperties.getDestinationCheck().isEnabled()",
                "final var settings = appProperties.getDestinationCheck();",
                "appProperties.getDigest().getWeekly().isEmail()",
                "final var campaign = appProperties.getCampaign();",
                "appProperties.getTrafficAlerts()\n        .getLinkClicks()",
                "final var burst = appProperties.getBurst();",
                "appProperties.getBurst().getLinks()",
                "appProperties.getModeration().isCustomAliases()",
                "final var moderation = appProperties.getModeration();",
                "final var abuseReports = appProperties.getAbuseReports();",
                "appProperties.getAbuseReports().getCc()"))
                .allMatch(SettingsSourceTest::readsAMigratedFlag);
        assertThat(List.of(
                "appProperties.getDestinationCheck().getBudget()",
                "appProperties.getDestinationCheck()\n        .isCatchUpOnStartup()",
                "appProperties.getBurst().getWindow()",
                "settingsService.isOn(SettingKey.NEW_SIGNUPS)"))
                .noneMatch(SettingsSourceTest::readsAMigratedFlag);
    }

    private static boolean readsAMigratedFlag(final String text) {
        return MIGRATED_READS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    private static Stream<String> migratedReads(final Source source) {
        return MIGRATED_READS.stream()
                .flatMap(pattern -> pattern.matcher(source.text()).results())
                .map(match -> source.path() + ":" + lineOf(source.text(), match.start()) + " " + match.group());
    }

    private static long lineOf(final String text, final int offset) {
        return text.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
    }

    private static Stream<Source> sources() {
        assertThat(MAIN_SOURCES).isDirectory();
        try (var paths = Files.walk(MAIN_SOURCES)) {
            return paths.filter(path -> path.toString().endsWith(".java")).map(SettingsSourceTest::read).toList().stream();
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
