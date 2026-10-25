package cc.hrva.urlshortener.service.stats;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class AppVersion {

    private static final String CHANGELOG = "changelog.json";
    private static final String UNKNOWN = "unknown";
    private static final String VERSION_PREFIX = "v";

    private final String current;

    @Autowired
    public AppVersion(final JsonMapper jsonMapper) {
        this(jsonMapper, new ClassPathResource(CHANGELOG));
    }

    AppVersion(final JsonMapper jsonMapper, final Resource changelog) {
        this.current = readNewestVersion(jsonMapper, changelog);
    }

    public String current() {
        return current;
    }

    private static String readNewestVersion(final JsonMapper jsonMapper, final Resource changelog) {
        try (final var input = changelog.getInputStream()) {
            final var version = jsonMapper.readTree(input).path("releases").path(0).path("version").stringValueOpt()
                    .map(String::strip)
                    .map(value -> Strings.CS.removeStart(value, VERSION_PREFIX))
                    .filter(StringUtils::isNotBlank);
            if (version.isEmpty()) {
                log.warn("The newest release in {} has no version", CHANGELOG);
            }
            return version.orElse(UNKNOWN);
        } catch (final IOException | RuntimeException exception) {
            log.warn("Failed to read the app version from {}", CHANGELOG, exception);
            return UNKNOWN;
        }
    }

}
