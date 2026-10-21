package cc.hrva.urlshortener.service.blocklist;

import java.util.Set;

public record ParsedBlocklist(Set<String> entries, String version, Long declaredEntries) {}
