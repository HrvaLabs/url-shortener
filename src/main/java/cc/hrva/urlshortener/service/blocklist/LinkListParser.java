package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.util.LinkKeys;
import java.io.BufferedReader;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

@Component
public class LinkListParser {

    private static final String NO_LINKS = "No links found. Use a list with one link per line or a CSV with a url column.";
    private static final Pattern LINK_START = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSV_FIELD = Pattern.compile(",(?:\"([^\"]*+(?:\"\"[^\"]*+)*+)\"|([^,]*+))");

    public ParsedBlocklist parse(final BufferedReader reader, final int maxLinks) {
        final var collector = new BlocklistCollector(maxLinks, "links", NO_LINKS);
        final var rows = new Rows();
        final var lines = reader.lines().iterator();
        while (lines.hasNext()) {
            final var line = lines.next().strip();
            if (BlocklistCollector.isComment(line)) {
                collector.readHeader(line);
                continue;
            }
            if (!line.isEmpty()) {
                rows.linkOf(line)
                        .flatMap(link -> LinkKeys.of(link).stream().findFirst())
                        .ifPresent(collector::add);
            }
        }
        return collector.parsed();
    }

    private static Optional<Integer> urlColumnOf(final String row) {
        if (LINK_START.matcher(row).lookingAt()) {
            return Optional.empty();
        }
        final var fields = csvFields(row);
        return IntStream.range(0, fields.size())
                .filter(index -> fields.get(index).equalsIgnoreCase("url"))
                .boxed()
                .findFirst();
    }

    static List<String> csvFields(final String row) {
        return CSV_FIELD.matcher("," + row).results()
                .map(field -> field.group(1) != null ? field.group(1).replace("\"\"", "\"") : field.group(2))
                .map(String::strip)
                .toList();
    }

    private static final class Rows {

        private boolean firstRowRead;
        private Integer urlColumn;

        private Optional<String> linkOf(final String row) {
            if (!firstRowRead) {
                firstRowRead = true;
                urlColumn = urlColumnOf(row).orElse(null);
                if (urlColumn != null) {
                    return Optional.empty();
                }
            }
            if (urlColumn == null) {
                return Optional.of(row);
            }
            final var fields = csvFields(row);
            return urlColumn < fields.size() ? Optional.of(fields.get(urlColumn)) : Optional.empty();
        }

    }

}
