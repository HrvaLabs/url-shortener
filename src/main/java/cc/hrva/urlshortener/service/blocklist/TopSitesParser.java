package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import cc.hrva.urlshortener.util.DomainNames;
import java.io.BufferedReader;
import java.util.HashSet;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class TopSitesParser {

    private static final String NO_DOMAINS = "No domains found. Use a top sites list with rank,domain lines.";
    private static final Pattern RANKED_DOMAIN = Pattern.compile("^\\d+,(.+)$");

    public ParsedBlocklist parse(final BufferedReader reader, final int limit) {
        final var domains = new HashSet<String>();
        final var lines = reader.lines().iterator();
        while (domains.size() < limit && lines.hasNext()) {
            domainOf(lines.next().strip()).ifPresent(domains::add);
        }
        if (domains.isEmpty()) {
            throw new BlocklistFormatException(NO_DOMAINS);
        }
        return new ParsedBlocklist(domains, null, null);
    }

    private static Optional<String> domainOf(final String line) {
        final var matcher = RANKED_DOMAIN.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            final var domain = DomainNames.toAscii(StringUtils.stripEnd(matcher.group(1).strip(), "."));
            return Optional.of(domain).filter(name -> DomainNames.HOST_NAME.matcher(name).matches() && !DomainNames.isIpLiteral(name));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

}
