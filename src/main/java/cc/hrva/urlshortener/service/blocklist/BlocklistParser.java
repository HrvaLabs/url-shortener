package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import cc.hrva.urlshortener.util.DomainNames;
import java.io.BufferedReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class BlocklistParser {

    private static final double MIN_PARSED_SHARE = 0.9;
    private static final Set<String> UNCONDITIONAL_OPTIONS = Set.of("important", "all", "document", "doc");

    private static final Pattern INLINE_COMMENT = Pattern.compile("\\s+#.*$");
    private static final Pattern HOSTS_PREFIX = Pattern.compile("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|::1)\\s+");
    private static final Pattern ADBLOCK_RULE = Pattern.compile("^\\|\\|([^\\^$|]+)(?:\\^\\|?)?(?:\\$(.+))?$");
    private static final Pattern IPV4_ADDRESS = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    private static final Pattern VERSION_HEADER = Pattern.compile(
            "^[#!]+\\s*version\\s*:\\s*(.{1,64})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECLARED_ENTRIES_HEADER = Pattern.compile(
            "^[#!]+\\s*(?:number of entries|number of unique domains|entries)\\s*:\\s*(\\d{1,3}(?:[,. ]?\\d{3}){0,5})$",
            Pattern.CASE_INSENSITIVE);

    public ParsedBlocklist parse(final BufferedReader reader, final int maxDomains) {
        final var domains = new HashSet<String>();
        final var header = new Header();
        final var lines = reader.lines().iterator();
        while (lines.hasNext()) {
            final var line = lines.next().strip();
            if (line.startsWith("#") || line.startsWith("!")) {
                header.read(line);
                continue;
            }
            domainOf(line).ifPresent(domains::add);
            if (domains.size() > maxDomains) {
                throw new BlocklistFormatException("The list has more than " + maxDomains + " domains");
            }
        }
        if (domains.isEmpty()) {
            throw new BlocklistFormatException("The list has no valid domains");
        }
        if (header.declaredEntries != null && domains.size() < header.declaredEntries * MIN_PARSED_SHARE) {
            throw new BlocklistFormatException("Only " + domains.size() + " of the " + header.declaredEntries
                    + " domains the list declares were read, so the download looks truncated");
        }
        return new ParsedBlocklist(domains, header.version, header.declaredEntries);
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

    private static Optional<String> headerValue(final Pattern field, final String comment) {
        final var matcher = field.matcher(comment);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static final class Header {

        private String version;
        private Long declaredEntries;

        private void read(final String comment) {
            if (version == null) {
                version = headerValue(VERSION_HEADER, comment).orElse(null);
            }
            if (declaredEntries == null) {
                declaredEntries = headerValue(DECLARED_ENTRIES_HEADER, comment)
                        .map(count -> Long.parseLong(StringUtils.getDigits(count)))
                        .orElse(null);
            }
        }

    }

}
