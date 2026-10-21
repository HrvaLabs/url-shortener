package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.RedirectFollower;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.RedirectChainService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DefaultRedirectChainService implements RedirectChainService {

    private static final int MAX_HOPS = 5;
    private static final Duration BUDGET = Duration.ofSeconds(5);
    private static final String PERFORMED_BY = "system";
    private static final String VIA = "redirect-check";
    private static final String VIA_REDIRECT_TO = " via redirect to ";
    private static final String REDIRECTS_TO = "redirects to ";
    private static final String REASON_SEPARATOR = ": ";
    private static final String THREAT_SEPARATOR = ", ";
    private static final int BLOCK_DETAIL_LENGTH = 255;
    private static final int FINAL_URL_LENGTH = 2048;

    private final UrlRepository urlRepository;
    private final RedirectFollower redirectFollower;
    private final LinkCheckService linkCheckService;
    private final SafeBrowsingService safeBrowsingService;
    private final ModerationService moderationService;
    private final ReviewQueueService reviewQueueService;
    private final Clock clock;

    @Override
    public void check(final long urlId) {
        final var url = urlRepository.findById(urlId).filter(Url::isActive);
        if (url.isEmpty()) {
            log.info("Link is no longer active, its redirects are not followed urlId={}", urlId);
            return;
        }

        final var longUrl = url.get().getLongUrl();
        final var chain = chainOf(url.get());
        if (chain.isEmpty()) {
            urlRepository.markRedirectChainChecked(urlId, longUrl, now());
            return;
        }

        final var hops = chain.get().stream().skip(1).filter(hop -> !hop.equals(longUrl)).distinct().toList();
        final var threats = hops.isEmpty() ? Optional.of(List.<String>of()) : threatsOf(urlId, hops);
        judge(url.get(), hops, threats.orElse(List.of()));
        if (threats.isEmpty()) {
            log.info("Safe Browsing was unavailable, the redirects are checked again in the next nightly scan urlId={}", urlId);
            return;
        }
        urlRepository.recordRedirectChain(urlId, longUrl, StringUtils.truncate(chain.get().getLast(), FINAL_URL_LENGTH), now());
    }

    @Async
    @Override
    public void checkLater(final long urlId) {
        check(urlId);
    }

    private Optional<List<String>> chainOf(final Url url) {
        try {
            return Optional.of(redirectFollower.followRedirects(url.getLongUrl(), MAX_HOPS, BUDGET));
        } catch (final UrlValidationException exception) {
            log.info("Could not follow the redirects of a link urlId={} reason={}", url.getId(), exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<List<String>> threatsOf(final long urlId, final List<String> hops) {
        try {
            return Optional.of(safeBrowsingService.checkUrlsForThreats(hops));
        } catch (final RuntimeException exception) {
            log.warn("Safe Browsing failed, the redirects are checked with the link checks only urlId={}", urlId, exception);
            return Optional.empty();
        }
    }

    private void judge(final Url url, final List<String> hops, final List<String> threats) {
        if (!threats.isEmpty()) {
            final var detail = threats.stream().distinct().collect(Collectors.joining(THREAT_SEPARATOR));
            block(url, BlockReason.SAFE_BROWSING, detail, hostOf(threats.getFirst()));
            return;
        }

        final var reasons = new ArrayList<String>();
        for (final var hop : hops) {
            final var verdict = linkCheckService.check(hop);
            if (blocksExistingLinks(verdict)) {
                block(url, verdict.blockReason(), verdict.blockDetail(), hostOf(hop));
                return;
            }
            if (verdict.outcome() == LinkVerdict.Outcome.BLOCK || verdict.outcome() == LinkVerdict.Outcome.FLAG) {
                verdict.reasons().forEach(reason -> reasons.add(REDIRECTS_TO + hostOf(hop) + REASON_SEPARATOR + reason));
            }
        }
        if (!reasons.isEmpty()) {
            reviewQueueService.flag(url, reasons);
        }
    }

    private void block(final Url url, final BlockReason reason, final String detail, final String host) {
        log.warn("Link blocked via a redirect urlId={} reason={} host={}", url.getId(), reason, host);
        moderationService.blockLink(url.getId(), reason, viaRedirect(detail, host), PERFORMED_BY, VIA);
    }

    private static boolean blocksExistingLinks(final LinkVerdict verdict) {
        return verdict.outcome() == LinkVerdict.Outcome.BLOCK && verdict.blockReason() != BlockReason.SCAM_SIGNALS;
    }

    private static String viaRedirect(final String detail, final String host) {
        final var suffix = VIA_REDIRECT_TO + host;
        final var room = Math.max(0, BLOCK_DETAIL_LENGTH - suffix.length());
        return StringUtils.truncate(StringUtils.truncate(detail, room) + suffix, BLOCK_DETAIL_LENGTH);
    }

    private static String hostOf(final String url) {
        return DomainNames.hostOf(url).orElse(url);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
    }

}
