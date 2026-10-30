package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.dto.ReputationResult;
import cc.hrva.urlshortener.dto.ReputationResult.Verdict;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.BlocklistAction;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.service.DomainReputationService;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.util.LinkKeys;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.With;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLinkCheckService implements LinkCheckService {

    private static final String YOUNG_DOMAIN = "domain registered in the last 30 days";
    private static final String UNREADABLE_HOST = "destination host could not be read";
    private static final String SPAMHAUS = "Spamhaus DBL: ";
    private static final String DETAIL_SEPARATOR = ", ";
    private static final Comparator<DomainRule> MOST_SPECIFIC_FIRST =
            Comparator.comparingInt((DomainRule rule) -> rule.getDomain().length()).reversed();

    private final DomainRuleRepository domainRuleRepository;
    private final BlocklistSourceRepository blocklistSourceRepository;
    private final BlocklistStore blocklistStore;
    private final DomainAgeService domainAgeService;
    private final DomainReputationService domainReputationService;
    private final SuspiciousUrlService suspiciousUrlService;

    @Override
    public LinkVerdict check(final String longUrl) {
        return evaluate(longUrl, false);
    }

    @Override
    public LinkVerdict explain(final String longUrl) {
        return evaluate(longUrl, true);
    }

    @Override
    public boolean isAllowListed(final String longUrl) {
        return mostSpecificRuleOf(longUrl).filter(rule -> rule.getType() == DomainRuleType.ALLOW).isPresent();
    }

    @Override
    public boolean isBlockedByRule(final String longUrl) {
        return mostSpecificRuleOf(longUrl).filter(rule -> rule.getType() == DomainRuleType.BLOCK).isPresent();
    }

    private LinkVerdict evaluate(final String longUrl, final boolean everySignal) {
        if (StringUtils.isBlank(longUrl)) {
            return LinkVerdict.clean();
        }
        final var host = DomainNames.hostOf(longUrl).orElse(null);
        if (host == null) {
            return new LinkVerdict(Outcome.FLAG, List.of(UNREADABLE_HOST), null, null);
        }

        final var ipLiteral = DomainNames.isIpLiteral(host);
        final var domains = ipLiteral ? List.<String>of() : DomainNames.candidates(host);
        final var cheapSignals = new Signals(
                matchingRules(domains), matchingSources(domains, longUrl), heuristicReasons(longUrl), ReputationResult.none(), false);
        final var withReputation = needsSlowLookup(cheapSignals, ipLiteral, everySignal)
                ? cheapSignals.withReputation(reputationOf(host))
                : cheapSignals;
        final var signals = needsSlowLookup(withReputation, ipLiteral, everySignal)
                ? withReputation.withYoung(isYoung(host))
                : withReputation;
        return signals.verdict();
    }

    private static boolean needsSlowLookup(final Signals signals, final boolean ipLiteral, final boolean everySignal) {
        return !ipLiteral && (everySignal || signals.undecided());
    }

    private Optional<DomainRule> mostSpecificRuleOf(final String longUrl) {
        return DomainNames.hostOf(longUrl)
                .filter(host -> !DomainNames.isIpLiteral(host))
                .flatMap(host -> matchingRules(DomainNames.candidates(host)).stream().findFirst());
    }

    private List<DomainRule> matchingRules(final List<String> domains) {
        if (domains.isEmpty()) {
            return List.of();
        }
        try {
            return domainRuleRepository.findByDomainIn(domains).stream().sorted(MOST_SPECIFIC_FIRST).toList();
        } catch (final RuntimeException exception) {
            log.warn("Domain rule lookup failed, checking without the rules exception={}", exception.getClass().getName());
            return List.of();
        }
    }

    private List<BlocklistSource> matchingSources(final List<String> domains, final String longUrl) {
        try {
            final var sources = blocklistSourceRepository.findByEnabledTrue();
            final var linkKeys = LinkKeys.of(longUrl);
            final var matches = blocklistStore.matchingSources(sources.stream().collect(Collectors.toMap(
                    BlocklistSource::getId, source -> source.getKind() == SourceKind.LINKS ? linkKeys : domains)));
            return sources.stream()
                    .filter(source -> matches.contains(source.getId()))
                    .sorted(Comparator.comparing(BlocklistSource::getId))
                    .toList();
        } catch (final RuntimeException exception) {
            log.warn("Blocklist source lookup failed, checking without the source lists exception={}", exception.getClass().getName());
            return List.of();
        }
    }

    private List<String> heuristicReasons(final String longUrl) {
        try {
            return suspiciousUrlService.findReasons(longUrl);
        } catch (final RuntimeException exception) {
            log.warn("Suspicious URL heuristics failed, checking without them exception={}", exception.getClass().getName());
            return List.of();
        }
    }

    private ReputationResult reputationOf(final String host) {
        try {
            return domainReputationService.lookup(host);
        } catch (final RuntimeException exception) {
            log.warn("Spamhaus DBL lookup failed, checking without it exception={}", exception.getClass().getName());
            return ReputationResult.none();
        }
    }

    private boolean isYoung(final String host) {
        try {
            return domainAgeService.isYoung(host);
        } catch (final RuntimeException exception) {
            log.warn("Domain age lookup failed, checking without it exception={}", exception.getClass().getName());
            return false;
        }
    }

    @With
    private record Signals(
            List<DomainRule> rules, List<BlocklistSource> sources, List<String> heuristics, ReputationResult reputation, boolean young) {

        private boolean undecided() {
            return !allowed() && blockOf(reasons()).isEmpty();
        }

        private LinkVerdict verdict() {
            final var reasons = reasons();
            if (allowed()) {
                return new LinkVerdict(Outcome.ALLOWED, reasons, null, null);
            }
            return blockOf(reasons).orElseGet(() -> reasons.isEmpty()
                    ? LinkVerdict.clean()
                    : new LinkVerdict(Outcome.FLAG, reasons, null, null));
        }

        private boolean allowed() {
            return allowRuleDecides() && blockingSource().isEmpty();
        }

        private boolean allowRuleDecides() {
            return rules.stream().findFirst().filter(rule -> rule.getType() == DomainRuleType.ALLOW).isPresent();
        }

        private Optional<BlocklistSource> blockingSource() {
            final var exactLinksOnly = allowRuleDecides();
            return sources.stream()
                    .filter(source -> source.getAction() == BlocklistAction.BLOCK)
                    .filter(source -> !exactLinksOnly || source.getKind() == SourceKind.LINKS)
                    .findFirst();
        }

        private Optional<LinkVerdict> blockOf(final List<String> reasons) {
            final var blockingRule = rules.stream().findFirst().filter(rule -> rule.getType() == DomainRuleType.BLOCK);
            if (blockingRule.isPresent()) {
                return Optional.of(blocked(reasons, BlockReason.DOMAIN_RULE, blockingRule.get().getDomain()));
            }
            final var blockingSource = blockingSource();
            if (blockingSource.isPresent()) {
                return Optional.of(blocked(reasons, BlockReason.BLOCKLIST, blockingSource.get().getName()));
            }
            if (reputation.verdict() == Verdict.BLOCK) {
                return Optional.of(blocked(reasons, BlockReason.BLOCKLIST, SPAMHAUS + reputation.type()));
            }
            final var scamSignals = heuristics.stream().filter(Signals::isScamSignal).toList();
            if (young && !scamSignals.isEmpty()) {
                final var detail = Stream.concat(Stream.of(YOUNG_DOMAIN), scamSignals.stream())
                        .collect(Collectors.joining(DETAIL_SEPARATOR));
                return Optional.of(blocked(reasons, BlockReason.SCAM_SIGNALS, detail));
            }
            return Optional.empty();
        }

        private List<String> reasons() {
            final var reasons = new ArrayList<String>();
            rules.forEach(rule -> reasons.add(
                    (rule.getType() == DomainRuleType.ALLOW ? "allowed domain " : "blocked domain ") + rule.getDomain()));
            sources.forEach(source -> reasons.add("on list " + source.getName()));
            if (reputation.verdict() != Verdict.NONE) {
                reasons.add(SPAMHAUS + reputation.type());
            }
            if (young) {
                reasons.add(YOUNG_DOMAIN);
            }
            reasons.addAll(heuristics);
            return List.copyOf(reasons);
        }

        private static boolean isScamSignal(final String reason) {
            return reason.startsWith(SuspiciousUrlService.IMITATES_PREFIX) || reason.equals(SuspiciousUrlService.LOGIN_LURE);
        }

        private static LinkVerdict blocked(final List<String> reasons, final BlockReason blockReason, final String detail) {
            return new LinkVerdict(Outcome.BLOCK, reasons, blockReason, detail);
        }

    }

}
