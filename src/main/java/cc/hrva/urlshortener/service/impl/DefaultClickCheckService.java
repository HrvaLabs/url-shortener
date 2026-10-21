package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.service.ClickCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.blocklist.BlocklistConfigurationChangedEvent;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.util.LinkKeys;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultClickCheckService implements ClickCheckService {

    private static final String SYSTEM = "system";
    private static final String VIA_CLICK = "click";

    private final BlocklistSourceRepository blocklistSourceRepository;
    private final DomainRuleRepository domainRuleRepository;
    private final BlocklistStore blocklistStore;
    private final ModerationService moderationService;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);

    @Override
    public boolean mayRedirect(final long urlId, final String longUrl) {
        final var lists = snapshot.get();
        final var lookups = lookupsFor(lists, longUrl);
        if (lookups.isEmpty()) {
            return true;
        }
        final var match = firstMatch(lists, lookups);
        if (match.isEmpty()) {
            return true;
        }
        block(urlId, match.get());
        return false;
    }

    @Override
    @Scheduled(fixedDelay = 300_000)
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reload() {
        try {
            final var sources = blocklistSourceRepository.findByEnabledTrue().stream()
                    .filter(source -> source.getAction() == BlocklistAction.BLOCK)
                    .toList();
            final var allowedDomains = domainRuleRepository.findByType(DomainRuleType.ALLOW).stream()
                    .map(DomainRule::getDomain)
                    .collect(Collectors.toUnmodifiableSet());
            snapshot.set(new Snapshot(namesOf(sources, SourceKind.DOMAINS), namesOf(sources, SourceKind.LINKS), allowedDomains));
        } catch (final RuntimeException exception) {
            log.warn("Could not reload the click-time blocklists, keeping the previous ones exception={}", exception.getClass().getName());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onBlocklistConfigurationChanged(final BlocklistConfigurationChangedEvent event) {
        reload();
    }

    private static Map<Long, List<String>> lookupsFor(final Snapshot lists, final String longUrl) {
        final var lookups = new HashMap<Long, List<String>>();
        final var domains = lists.domainSources().isEmpty() ? List.<String>of() : domainsOf(longUrl);
        if (!domains.isEmpty() && domains.stream().noneMatch(lists.allowedDomains()::contains)) {
            lists.domainSources().keySet().forEach(id -> lookups.put(id, domains));
        }
        final var linkKeys = lists.linkSources().isEmpty() ? List.<String>of() : LinkKeys.of(longUrl);
        if (!linkKeys.isEmpty()) {
            lists.linkSources().keySet().forEach(id -> lookups.put(id, linkKeys));
        }
        return lookups;
    }

    private static List<String> domainsOf(final String longUrl) {
        return DomainNames.hostOf(longUrl)
                .filter(host -> !DomainNames.isIpLiteral(host))
                .map(DomainNames::candidates)
                .orElse(List.of());
    }

    private Optional<String> firstMatch(final Snapshot lists, final Map<Long, List<String>> lookups) {
        try {
            return blocklistStore.matchingSources(lookups).stream()
                    .min(Comparator.naturalOrder())
                    .map(lists::nameOf);
        } catch (final RuntimeException exception) {
            log.warn("Click-time blocklist lookup failed, letting the visitor through exception={}", exception.getClass().getName());
            return Optional.empty();
        }
    }

    private void block(final long urlId, final String sourceName) {
        try {
            moderationService.blockLink(urlId, BlockReason.BLOCKLIST, sourceName, SYSTEM, VIA_CLICK);
        } catch (final RuntimeException exception) {
            log.warn("Blocking a link found on a blocklist at click time failed, refusing the redirect anyway urlId={}", urlId, exception);
        }
    }

    private static Map<Long, String> namesOf(final List<BlocklistSource> sources, final SourceKind kind) {
        return sources.stream()
                .filter(source -> source.getKind() == kind)
                .collect(Collectors.toUnmodifiableMap(BlocklistSource::getId, BlocklistSource::getName));
    }

    private record Snapshot(Map<Long, String> domainSources, Map<Long, String> linkSources, Set<String> allowedDomains) {

        private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Set.of());

        private String nameOf(final long sourceId) {
            return domainSources.getOrDefault(sourceId, linkSources.get(sourceId));
        }

    }

}
