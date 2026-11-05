package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.exception.BlocklistFormatException;
import cc.hrva.urlshortener.util.BrandNames;
import cc.hrva.urlshortener.util.DomainNames;
import java.io.BufferedReader;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BrandSitesParser {

    private static final String NO_BRAND_SITES = "No brand sites found in the top sites list.";

    private final TopSitesParser topSitesParser;

    public ParsedBlocklist parse(final BufferedReader reader, final int limit) {
        final var brandSites = topSitesParser.parse(reader, limit).entries().stream()
                .filter(domain -> DomainNames.registryDomain(domain).equals(domain) && BrandNames.ALL.contains(DomainNames.registryNameOf(domain)))
                .collect(Collectors.toSet());
        if (brandSites.isEmpty()) {
            throw new BlocklistFormatException(NO_BRAND_SITES);
        }
        return new ParsedBlocklist(brandSites, null, null);
    }

}
