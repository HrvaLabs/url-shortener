package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SafeBrowsingRecheckService;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSafeBrowsingRecheckService implements SafeBrowsingRecheckService {

    private static final int BATCH_SIZE = 20;
    private static final String PERFORMED_BY = "system";
    private static final String VIA = "nightly";
    private static final String THREAT_SEPARATOR = ", ";
    private static final int BLOCK_DETAIL_LENGTH = 255;

    private final UrlRepository urlRepository;
    private final SafeBrowsingService safeBrowsingService;
    private final LinkCheckService linkCheckService;
    private final ModerationService moderationService;
    private final ReviewQueueService reviewQueueService;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recheckActiveUrls() {
        log.info("Starting nightly scan of active URLs");

        final var ids = urlRepository.findActiveIds();
        final var tally = new EnumMap<ScanResult, Integer>(ScanResult.class);
        final var safeBrowsingAvailable = new AtomicBoolean(true);
        var checked = 0;

        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            final var urls = urlRepository.findAllById(ids.subList(from, Math.min(from + BATCH_SIZE, ids.size())));
            final var safeBrowsingChecked = new ArrayList<Long>();

            for (final var url : urls) {
                if (!url.isActive()) {
                    continue;
                }

                try {
                    final var threats = threatsOf(url, safeBrowsingAvailable);
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
    }

    private Optional<List<String>> threatsOf(final Url url, final AtomicBoolean safeBrowsingAvailable) {
        if (!safeBrowsingAvailable.get()) {
            return Optional.empty();
        }
        try {
            return Optional.of(safeBrowsingService.checkUrlsForThreats(url.getLongUrl()));
        } catch (final RuntimeException exception) {
            safeBrowsingAvailable.set(false);
            log.warn("Safe Browsing failed, the nightly scan goes on with the link checks only urlId={}", url.getId(), exception);
            return Optional.empty();
        }
    }

    private ScanResult scan(final Url url, final List<String> threats) {
        if (!threats.isEmpty()) {
            log.warn("URL /{} flagged as malicious: {}", url.getShortUrl(), threats);
            final var detail = threats.stream().distinct().collect(Collectors.joining(THREAT_SEPARATOR));
            moderationService.blockLink(url.getId(), BlockReason.SAFE_BROWSING, StringUtils.truncate(detail, BLOCK_DETAIL_LENGTH), PERFORMED_BY, VIA);
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
