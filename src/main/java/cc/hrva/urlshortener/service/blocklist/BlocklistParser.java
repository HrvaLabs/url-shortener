package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import cc.hrva.urlshortener.util.DomainNames;
import java.io.BufferedReader;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class BlocklistParser {

    private static final Pattern INLINE_COMMENT = Pattern.compile("\\s+#.*$");
    private static final Pattern HOSTS_PREFIX = Pattern.compile("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|::1)\\s+");
    private static final Pattern IPV4_ADDRESS = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    public Set<String> parse(final BufferedReader reader, final int maxDomains) {
        final var domains = new HashSet<String>();
        final var lines = reader.lines().iterator();
        while (lines.hasNext()) {
            domainOf(lines.next()).ifPresent(domains::add);
            if (domains.size() > maxDomains) {
                throw new BlocklistFormatException("The list has more than " + maxDomains + " domains");
            }
        }
        if (domains.isEmpty()) {
            throw new BlocklistFormatException("The list has no valid domains");
        }
        return domains;
    }

    private static Optional<String> domainOf(final String line) {
        final var trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return Optional.empty();
        }
        final var withoutComment = INLINE_COMMENT.matcher(trimmed).replaceFirst("");
        final var withoutHostsPrefix = HOSTS_PREFIX.matcher(withoutComment).replaceFirst("");
        final var withoutAdblockSyntax = StringUtils.substringBefore(StringUtils.removeStart(withoutHostsPrefix, "||"), "^");
        final var domain = StringUtils.removeEnd(StringUtils.removeStart(withoutAdblockSyntax, "*."), ".");
        if (!DomainNames.HOST_NAME.matcher(domain).matches() || IPV4_ADDRESS.matcher(domain).matches()) {
            return Optional.empty();
        }
        return Optional.of(domain.toLowerCase(Locale.ROOT));
    }

}
