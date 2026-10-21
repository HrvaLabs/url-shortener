package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

final class BlocklistCollector {

    private static final double MIN_PARSED_SHARE = 0.9;

    private static final Pattern VERSION_HEADER = Pattern.compile(
            "^[#!]+\\s*version\\s*:\\s*(.{1,64})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DECLARED_ENTRIES_HEADER = Pattern.compile(
            "^[#!]+\\s*(?:number of entries|number of unique domains|entries)\\s*:\\s*(\\d{1,3}(?:[,. ]?\\d{3}){0,5})$",
            Pattern.CASE_INSENSITIVE);

    private final Set<String> entries = new HashSet<>();
    private final int maxEntries;
    private final String entryName;
    private final String emptyMessage;
    private String version;
    private Long declaredEntries;

    BlocklistCollector(final int maxEntries, final String entryName, final String emptyMessage) {
        this.maxEntries = maxEntries;
        this.entryName = entryName;
        this.emptyMessage = emptyMessage;
    }

    static boolean isComment(final String line) {
        return line.startsWith("#") || line.startsWith("!");
    }

    void readHeader(final String comment) {
        if (version == null) {
            version = headerValue(VERSION_HEADER, comment).orElse(null);
        }
        if (declaredEntries == null) {
            declaredEntries = headerValue(DECLARED_ENTRIES_HEADER, comment)
                    .map(count -> Long.parseLong(StringUtils.getDigits(count)))
                    .orElse(null);
        }
    }

    void add(final String entry) {
        entries.add(entry);
        if (entries.size() > maxEntries) {
            throw new BlocklistFormatException("The list has more than " + maxEntries + " " + entryName);
        }
    }

    ParsedBlocklist parsed() {
        if (entries.isEmpty()) {
            throw new BlocklistFormatException(emptyMessage);
        }
        if (declaredEntries != null && entries.size() < declaredEntries * MIN_PARSED_SHARE) {
            throw new BlocklistFormatException("Only " + entries.size() + " of the " + declaredEntries + " "
                    + entryName + " the list declares were read, so the download looks truncated");
        }
        return new ParsedBlocklist(entries, version, declaredEntries);
    }

    private static Optional<String> headerValue(final Pattern field, final String comment) {
        final var matcher = field.matcher(comment);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

}
