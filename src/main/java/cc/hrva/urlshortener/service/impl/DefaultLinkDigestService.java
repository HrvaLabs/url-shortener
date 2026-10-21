package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.DecisionsDigest;
import cc.hrva.urlshortener.dto.LinkDigestEntry;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.LinkDigestService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLinkDigestService implements LinkDigestService {

    private static final int NEWEST_OPEN_ENTRIES = 10;
    private static final int MAX_POSSIBLE_SCAMMERS = 10;
    private static final Set<BlockReason> MANUAL_BLOCKS = Set.of(BlockReason.ADMIN, BlockReason.USER_BLOCKED);

    private final ReviewQueueService reviewQueueService;
    private final ScammerSignalService scammerSignalService;
    private final BlocklistSourceService blocklistSourceService;
    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final SendingEmailService sendingEmailService;

    @Override
    @Transactional(readOnly = true)
    public void sendDailyDigest() {
        final var now = LocalDateTime.now();
        final var digest = new DecisionsDigest(
                reviewQueueService.openCount(),
                reviewQueueService.openReportCount(),
                newestOpen(),
                possibleScammers(),
                autoBlockedSince(now.minusDays(1)),
                failingSources(),
                newAccountLinks(now.minusDays(1), now.minusDays(7)));

        if (!needsAttention(digest)) {
            log.info("Decisions email: nothing waiting");
            return;
        }

        log.info("Decisions email: {} open, {} blocked automatically, {} failing sources",
                digest.openCount(), digest.autoBlocked().size(), digest.failingSources().size());
        sendingEmailService.sendDecisionsEmailToAdmin(digest);
    }

    private static boolean needsAttention(final DecisionsDigest digest) {
        return digest.openCount() > 0 || !digest.autoBlocked().isEmpty() || !digest.failingSources().isEmpty();
    }

    private List<LinkDigestEntry> newestOpen() {
        return reviewQueueService.open(PageRequest.of(0, NEWEST_OPEN_ENTRIES), null).stream()
                .map(DefaultLinkDigestService::toEntry)
                .toList();
    }

    private static LinkDigestEntry toEntry(final LinkReview review) {
        final var url = review.getUrl();
        final var reasons = review.getReasons().lines().map(String::strip).filter(StringUtils::isNotEmpty).toList();
        return new LinkDigestEntry(url.getId(), url.getShortUrl(), destinationOf(url), ownerEmailOf(url), reasons);
    }

    private List<DecisionsDigest.PossibleScammer> possibleScammers() {
        final var ids = scammerSignalService.possibleScammerIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        return userRepository.findAllById(ids).stream()
                .sorted(Comparator.comparing(User::getCreateDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_POSSIBLE_SCAMMERS)
                .map(user -> new DecisionsDigest.PossibleScammer(
                        user.getEmail(), urlRepository.countByOwnerAndBlockReasonIsNotNull(user), urlRepository.countFlaggedByOwner(user)))
                .toList();
    }

    private List<DecisionsDigest.BlockedLink> autoBlockedSince(final LocalDateTime since) {
        return urlRepository.findTop20ByBlockedAtAfterAndBlockReasonNotInOrderByBlockedAtDesc(since, MANUAL_BLOCKS).stream()
                .map(url -> new DecisionsDigest.BlockedLink(url.getShortUrl(), destinationOf(url), reasonInWords(url)))
                .toList();
    }

    private List<DecisionsDigest.FailingSource> failingSources() {
        return blocklistSourceService.list().stream()
                .filter(source -> source.lastError() != null)
                .map(source -> new DecisionsDigest.FailingSource(source.name(), source.lastError()))
                .toList();
    }

    private List<DecisionsDigest.NewAccountLink> newAccountLinks(final LocalDateTime createdAfter, final LocalDateTime ownerCreatedAfter) {
        return urlRepository.findTop10ByCreateDateAfterAndOwnerCreateDateAfterOrderByCreateDateDesc(createdAfter, ownerCreatedAfter).stream()
                .map(url -> new DecisionsDigest.NewAccountLink(url.getShortUrl(), destinationOf(url), ownerEmailOf(url)))
                .toList();
    }

    private static String reasonInWords(final Url url) {
        final var words = switch (url.getBlockReason()) {
            case SAFE_BROWSING -> "Google Safe Browsing";
            case BLOCKLIST -> "a blocklist";
            case DOMAIN_RULE -> "a domain rule";
            case SCAM_SIGNALS -> "scam signals";
            case ADMIN -> "an admin";
            case USER_BLOCKED -> "a blocked account";
        };
        return StringUtils.isBlank(url.getBlockDetail()) ? words : words + " (" + url.getBlockDetail() + ")";
    }

    private static String ownerEmailOf(final Url url) {
        return url.getOwner() != null ? url.getOwner().getEmail() : null;
    }

    private static String destinationOf(final Url url) {
        final var withoutQuery = StringUtils.substringBefore(StringUtils.substringBefore(StringUtils.defaultString(url.getLongUrl()), "#"), "?");
        final var withoutScheme = withoutQuery.contains("://") ? StringUtils.substringAfter(withoutQuery, "://") : withoutQuery;
        final var authority = StringUtils.substringBefore(withoutScheme, "/");
        final var path = StringUtils.removeEnd(withoutScheme.substring(authority.length()), "/");
        return DomainNames.hostOf(authority).orElse(authority) + path;
    }

}
