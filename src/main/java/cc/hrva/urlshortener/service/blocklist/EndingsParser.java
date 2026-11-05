package cc.hrva.urlshortener.service.blocklist;

import java.io.BufferedReader;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class EndingsParser {

    private static final String NO_ENDINGS = "No endings found. Use a list with one top-level domain per line.";
    private static final Pattern ENDING = Pattern.compile("^(?!-)[a-z0-9-]{1,63}(?<!-)$");

    public ParsedBlocklist parse(final BufferedReader reader, final int limit) {
        final var collector = new BlocklistCollector(limit, "endings", NO_ENDINGS);
        final var lines = reader.lines().iterator();
        while (lines.hasNext()) {
            final var line = lines.next().strip();
            if (BlocklistCollector.isComment(line)) {
                collector.readHeader(line);
                continue;
            }
            endingOf(line).ifPresent(collector::add);
        }
        return collector.parsed();
    }

    private static Optional<String> endingOf(final String line) {
        final var ending = StringUtils.removeStart(StringUtils.removeStart(line, "*."), ".").toLowerCase(Locale.ROOT);
        return Optional.of(ending).filter(name -> ENDING.matcher(name).matches());
    }

}
