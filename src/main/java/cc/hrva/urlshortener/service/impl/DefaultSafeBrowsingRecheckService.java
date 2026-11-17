package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.RedirectChainService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SafeBrowsingRecheckService;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.jobs.JobFailedException;
import cc.hrva.urlshortener.service.notification.HostPacer;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSafeBrowsingRecheckService implements SafeBrowsingRecheckService {

    private static final int BATCH_SIZE = 20;
    private static final int REDIRECT_CHECKS_PER_RUN = 300;
    private static final int SHORTENER_CHECKS_PER_RUN = 200;
    private static final int SHORTENER_RECHECK_INTERVAL_DAYS = 1;
    private static final int REDIRECT_CHECK_INTERVAL_DAYS = 7;
    private static final String PERFORMED_BY = "system";
    private static final String VIA = "nightly";
    private static final String THREAT_SEPARATOR = ", ";
    private static final String SAFE_BROWSING_UNAVAILABLE = "Safe Browsing was unavailable";
    private static final String SAFE_BROWSING_HOST = "safebrowsing.googleapis.com";
    private static final Duration SAFE_BROWSING_PACE = Duration.ofMillis(500);

    private final UrlRepository urlRepository;
    private final SafeBrowsingService safeBrowsingService;
    private final LinkCheckService linkCheckService;
    private final ModerationService moderationService;
    private final ReviewQueueService reviewQueueService;
    private final RedirectChainService redirectChainService;
    private final SettingsService settingsService;
    private final Clock clock;
    private final HostPacer.Sleeper hostPacerSleeper;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recheckActiveUrls() {
        log.info("Starting nightly scan of active URLs");

        final var ids = urlRepository.findActiveIds();
        final var tally = new EnumMap<ScanResult, Integer>(ScanResult.class);
        final var safeBrowsingAvailable = new AtomicBoolean(true);
        final var pacer = new HostPacer(clock, hostPacerSleeper, SAFE_BROWSING_PACE);
        var checked = 0;

        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            final var urls = urlRepository.findAllById(ids.subList(from, Math.min(from + BATCH_SIZE, ids.size()))).stream()
                    .filter(Url::isActive)
                    .toList();
            final var threatsByLink = threatsOf(urls, safeBrowsingAvailable, pacer);
            final var safeBrowsingChecked = new ArrayList<Long>();

            for (final var url : urls) {
                try {
                    final var threats = Optional.ofNullable(threatsByLink.get(url.getId()));
                    tally.merge(scan(url, threats.orElse(List.of())), 1, Integer::sum);
                    if (threats.isPresent()) {
                        safeBrowsingChecked.add(url.getId());
                    }
                    checked++;
                } catch (final RuntimeException exception) {
                    log.warn("Nightly scan of a link failed, continuing with the next one urlId={}", url.getId(), exception);
                }
            }

            if (!safeBrowsingChecked.isEmpty()) {
                urlRepository.markSafeBrowsingChecked(safeBrowsingChecked, LocalDateTime.now());
            }
            log.debug("Nightly scan progress: {}/{} checked, {}", checked, ids.size(), tally);
        }

        log.info("Nightly scan complete — {} checked, {} blocked, {} queued for review, safeBrowsingAvailable={}",
                checked, tally.getOrDefault(ScanResult.BLOCKED, 0), tally.getOrDefault(ScanResult.QUEUED, 0), safeBrowsingAvailable.get());

        if (!safeBrowsingAvailable.get()) {
            throw new JobFailedException(SAFE_BROWSING_UNAVAILABLE + " during the link scan, so links were checked without it and the redirect checks wait for the next night");
        }
        if (!checkRedirectChains(pacer)) {
            throw new JobFailedException(SAFE_BROWSING_UNAVAILABLE + " during the redirect checks, so the rest wait for the next night");
        }
    }

    private boolean checkRedirectChains(final HostPacer pacer) {
        final var checkedBefore = LocalDateTime.now().minusDays(REDIRECT_CHECK_INTERVAL_DAYS);
        final var ids = urlRepository.findIdsDueForRedirectCheck(checkedBefore, PageRequest.ofSize(REDIRECT_CHECKS_PER_RUN));
        final var followedIds = new HashSet<Long>();
        final var safeBrowsingAvailable = follow(ids, followedIds, pacer);
        log.info("Nightly redirect checks complete — {} of {} due links followed", followedIds.size(), ids.size());
        return safeBrowsingAvailable && checkShortenerLinks(Set.copyOf(ids), pacer);
    }

    private boolean checkShortenerLinks(final Set<Long> attempted, final HostPacer pacer) {
        final var checkedBefore = LocalDateTime.now().minusDays(SHORTENER_RECHECK_INTERVAL_DAYS);
        final var ids = urlRepository.findIdsOfShortenerLinksDueForRedirectCheck(checkedBefore, PageRequest.ofSize(SHORTENER_CHECKS_PER_RUN));
        final var leftover = urlRepository.countShortenerLinksDueForRedirectCheck(checkedBefore) - ids.size();
        final var due = ids.stream().filter(id -> !attempted.contains(id)).toList();
        final var followedIds = new HashSet<Long>();
        final var safeBrowsingAvailable = follow(due, followedIds, pacer);
        log.info("Nightly shortener redirect checks complete — {} of {} due links followed", followedIds.size(), due.size());
        if (leftover > 0) {
            log.info("Nightly shortener redirect checks left {} links for the next night", leftover);
        }
        return safeBrowsingAvailable;
    }

    private boolean follow(final List<Long> ids, final Set<Long> followedIds, final HostPacer pacer) {
        for (final var id : ids) {
            try {
                pacer.awaitTurn(SAFE_BROWSING_HOST);
                if (redirectChainService.check(id) == RedirectChainService.Result.SAFE_BROWSING_UNAVAILABLE) {
                    log.warn("Safe Browsing became unavailable, the remaining redirect checks wait for the next nightly scan urlId={}", id);
                    return false;
                }
                followedIds.add(id);
            } catch (final RuntimeException exception) {
                log.warn("Redirect check of a link failed, continuing with the next one urlId={}", id, exception);
            }
        }
        return true;
    }

    private Map<Long, List<String>> threatsOf(final List<Url> urls, final AtomicBoolean safeBrowsingAvailable, final HostPacer pacer) {
        final var threatsByLink = new HashMap<Long, List<String>>();
        if (urls.isEmpty() || !settingsService.isOn(SettingKey.NIGHTLY_SAFE_BROWSING) || !safeBrowsingAvailable.get()) {
            return threatsByLink;
        }
        final var longUrls = urls.stream().map(Url::getLongUrl).distinct().toList();
        final var chunkThreats = lookUp(() -> safeBrowsingService.checkUrlsForThreats(longUrls), urls.getFirst().getId(), urls.size(),
                safeBrowsingAvailable, pacer);
        if (chunkThreats.isEmpty()) {
            return threatsByLink;
        }
        if (chunkThreats.get().isEmpty()) {
            urls.forEach(url -> threatsByLink.put(url.getId(), List.of()));
            return threatsByLink;
        }
        for (final var url : urls) {
            final var threats = lookUp(() -> safeBrowsingService.checkUrlsForThreats(url.getLongUrl()), url.getId(), 1, safeBrowsingAvailable, pacer);
            if (threats.isEmpty()) {
                return threatsByLink;
            }
            threatsByLink.put(url.getId(), threats.get());
        }
        if (threatsByLink.values().stream().allMatch(List::isEmpty)) {
            log.warn("Safe Browsing named a threat for a chunk but for none of its links alone fromUrlId={} links={} threats={}",
                    urls.getFirst().getId(), urls.size(), chunkThreats.get());
        }
        return threatsByLink;
    }

    private static Optional<List<String>> lookUp(final Supplier<List<String>> lookup, final long fromUrlId, final int links,
            final AtomicBoolean safeBrowsingAvailable, final HostPacer pacer) {
        pacer.awaitTurn(SAFE_BROWSING_HOST);
        try {
            return Optional.of(lookup.get());
        } catch (final RuntimeException exception) {
            safeBrowsingAvailable.set(false);
            log.warn("Safe Browsing failed, the nightly scan goes on with the link checks only fromUrlId={} links={} reason={}",
                    fromUrlId, links, exception.getMessage());
            return Optional.empty();
        }
    }

    private ScanResult scan(final Url url, final List<String> threats) {
        if (!threats.isEmpty()) {
            log.warn("URL /{} flagged as malicious: {}", url.getShortUrl(), threats);
            final var detail = threats.stream().distinct().collect(Collectors.joining(THREAT_SEPARATOR));
            moderationService.blockLink(url.getId(), BlockReason.SAFE_BROWSING, detail, PERFORMED_BY, VIA);
            return ScanResult.BLOCKED;
        }

        final var verdict = linkCheckService.check(url.getLongUrl());
        if (blocksExistingLinks(verdict)) {
            log.warn("URL /{} blocked by the link check reason={} detail={}", url.getShortUrl(), verdict.blockReason(), verdict.blockDetail());
            moderationService.blockLink(url.getId(), verdict.blockReason(), verdict.blockDetail(), PERFORMED_BY, VIA);
            return ScanResult.BLOCKED;
        }

        if (verdict.outcome() == LinkVerdict.Outcome.BLOCK || verdict.outcome() == LinkVerdict.Outcome.FLAG) {
            reviewQueueService.flag(url, verdict.reasons());
            return ScanResult.QUEUED;
        }
        return ScanResult.PASSED;
    }

    private static boolean blocksExistingLinks(final LinkVerdict verdict) {
        return verdict.outcome() == LinkVerdict.Outcome.BLOCK && verdict.blockReason() != BlockReason.SCAM_SIGNALS;
    }

    private enum ScanResult {
        BLOCKED, QUEUED, PASSED
    }

}
