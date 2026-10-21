package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.util.DomainNames;
import java.io.BufferedReader;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class BlocklistParser {

    private static final String NO_DOMAINS = "No domains found. Use the list's domains, hosts or adblock format.";
    private static final Set<String> UNCONDITIONAL_OPTIONS = Set.of("important", "all", "document", "doc");

    private static final Pattern INLINE_COMMENT = Pattern.compile("\\s+#.*$");
    private static final Pattern HOSTS_PREFIX = Pattern.compile("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|::1)\\s+");
    private static final Pattern ADBLOCK_RULE = Pattern.compile("^\\|\\|([^\\^$|]+)(?:\\^\\|?)?(?:\\$(.+))?$");
    private static final Pattern IPV4_ADDRESS = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    public ParsedBlocklist parse(final BufferedReader reader, final int maxDomains) {
        final var collector = new BlocklistCollector(maxDomains, "domains", NO_DOMAINS);
        final var lines = reader.lines().iterator();
        while (lines.hasNext()) {
            final var line = lines.next().strip();
            if (BlocklistCollector.isComment(line)) {
                collector.readHeader(line);
                continue;
            }
            domainOf(line).ifPresent(collector::add);
        }
        return collector.parsed();
    }

    private static Optional<String> domainOf(final String line) {
        final var withoutComment = INLINE_COMMENT.matcher(line).replaceFirst("");
        final var name = withoutComment.startsWith("||")
                ? adblockDomainOf(withoutComment)
                : Optional.of(StringUtils.removeStart(HOSTS_PREFIX.matcher(withoutComment).replaceFirst(""), "*."));
        return name.map(value -> StringUtils.stripEnd(value, "."))
                .filter(BlocklistParser::isDomain)
                .map(domain -> domain.toLowerCase(Locale.ROOT));
    }

    private static Optional<String> adblockDomainOf(final String rule) {
        final var matcher = ADBLOCK_RULE.matcher(rule);
        if (!matcher.matches() || (matcher.group(2) != null && !isUnconditional(matcher.group(2)))) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1));
    }

    private static boolean isUnconditional(final String options) {
        return Arrays.stream(options.split(","))
                .map(option -> option.strip().toLowerCase(Locale.ROOT))
                .allMatch(UNCONDITIONAL_OPTIONS::contains);
    }

    private static boolean isDomain(final String name) {
        return DomainNames.HOST_NAME.matcher(name).matches() && !IPV4_ADDRESS.matcher(name).matches();
    }

}
