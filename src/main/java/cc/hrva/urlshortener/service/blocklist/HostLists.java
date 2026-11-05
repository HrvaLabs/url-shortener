package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.service.blocklist.HostListing.BrandSite;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HostLists {

    private final BlocklistSourceRepository blocklistSourceRepository;
    private final BlocklistStore blocklistStore;

    public HostListing lookup(final String host) {
        final var listedForm = StringUtils.stripEnd(StringUtils.defaultString(host).strip().toLowerCase(Locale.ROOT), ".");
        if (listedForm.isEmpty()) {
            return HostListing.unknown();
        }
        if (DomainNames.isIpLiteral(listedForm)) {
            return new HostListing(false, false, BrandSite.NOT_LISTED);
        }
        try {
            return lookupListed(listedForm);
        } catch (final RuntimeException exception) {
            log.warn("Host list lookup failed, checking without the lists exception={}", exception.getClass().getName());
            return HostListing.unknown();
        }
    }

    private HostListing lookupListed(final String host) {
        final var keysBySource = new HashMap<Long, List<String>>();
        final var shortenerIds = new HashSet<Long>();
        final var endingIds = new HashSet<Long>();
        final var brandIds = new HashSet<Long>();
        for (final var source : blocklistSourceRepository.findByEnabledTrue()) {
            final var kind = source.getKind();
            if (kind == SourceKind.SHORTENERS) {
                keysBySource.put(source.getId(), DomainNames.candidates(host));
                shortenerIds.add(source.getId());
            } else if (kind == SourceKind.ENDINGS) {
                keysBySource.put(source.getId(), List.of(host.substring(host.lastIndexOf('.') + 1)));
                endingIds.add(source.getId());
            } else if (kind == SourceKind.BRAND_SITES && blocklistStore.exists(source.getId())) {
                keysBySource.put(source.getId(), List.of(DomainNames.registryDomain(host)));
                brandIds.add(source.getId());
            }
        }
        if (keysBySource.isEmpty()) {
            return HostListing.unknown();
        }
        final var matching = blocklistStore.matchingSources(keysBySource);
        final var brandSite = brandIds.isEmpty() ? BrandSite.UNKNOWN
                : matching.stream().anyMatch(brandIds::contains) ? BrandSite.LISTED : BrandSite.NOT_LISTED;
        return new HostListing(matching.stream().anyMatch(shortenerIds::contains),
                matching.stream().anyMatch(endingIds::contains), brandSite);
    }

}
