package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse.DomainRename;
import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.UrlDomainMoves;
import cc.hrva.urlshortener.repository.UrlDomainMoves.DomainLink;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.UrlService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainRemoval {

    private static final String NOT_FOUND = "Custom domain not found";
    private static final String NO_MOVE_AFTER_TURN_OFF =
            "This domain was turned off by hrva.cc, so its links can't move to hrva.cc. Remove it with its links turned off instead.";
    private static final Set<DomainDisableReason> TURNED_OFF_BY_HRVA = EnumSet.of(DomainDisableReason.ADMIN, DomainDisableReason.DOMAIN_RULE,
            DomainDisableReason.BLOCKLIST);

    private final CustomDomainRepository customDomainRepository;
    private final UrlDomainMoves urlDomainMoves;
    private final UrlRepository urlRepository;
    private final UrlService urlService;
    private final ReservedAliasService reservedAliasService;
    private final UrlCache urlCache;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DomainRemovalResponse remove(final long domainId, final DomainLinkMode mode, final Actor actor) {
        final var marked = Objects.requireNonNull(transactionOperations.execute(status -> mark(domainId, mode, actor)));
        final var response = mode == DomainLinkMode.MOVE ? moveAll(marked) : new DomainRemovalResponse(0, List.of(), marked.deactivated());
        marked.links().forEach(link -> urlCache.evict(UrlCacheKeys.of(domainId, link.shortUrl())));
        log.info("Custom domain removed domainId={} from={} byAdmin={} mode={} links={} moved={} renamed={} deactivated={}", domainId,
                marked.from(), actor.admin(), mode, marked.links().size(), response.moved(), response.renamed().size(), response.deactivated());
        return response;
    }

    private Marked mark(final long domainId, final DomainLinkMode mode, final Actor actor) {
        final var domain = customDomainRepository.findByIdForUpdate(domainId)
                .filter(found -> found.getStatus() != CustomDomainStatus.DELETED)
                .orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
        final var from = domain.getStatus();
        if (mode == DomainLinkMode.MOVE && !actor.admin() && from == CustomDomainStatus.DISABLED
                && TURNED_OFF_BY_HRVA.contains(domain.getDisabledReason())) {
            log.info("Custom domain removal with its links moved refused, hrva.cc turned it off domainId={} reason={}", domainId,
                    domain.getDisabledReason());
            throw new CustomDomainConflictException(NO_MOVE_AFTER_TURN_OFF);
        }
        final var links = from == CustomDomainStatus.PENDING_REVIEW ? List.<DomainLink>of() : urlDomainMoves.linksOf(domainId);
        if (customDomainRepository.markDeleted(domainId, from, LocalDateTime.now(clock)) != 1) {
            log.warn("Custom domain could not be marked removed under its lock domainId={} from={}", domainId, from);
            throw new CustomDomainNotFoundException(NOT_FOUND);
        }
        final var deactivated = mode == DomainLinkMode.DEACTIVATE && !links.isEmpty() ? urlDomainMoves.deactivateAll(domainId) : 0;
        eventPublisher.publishEvent(new CustomDomainChangedEvent(domainId));
        return new Marked(domainId, from, links, deactivated);
    }

    private DomainRemovalResponse moveAll(final Marked marked) {
        var moved = 0;
        final var renamed = new ArrayList<DomainRename>();
        for (final var link : marked.links()) {
            final var alias = moveOne(marked.domainId(), link);
            if (alias.isEmpty()) {
                continue;
            }
            moved++;
            if (!alias.get().equals(link.shortUrl())) {
                renamed.add(new DomainRename(link.shortUrl(), alias.get()));
            }
            urlCache.evict(UrlCacheKeys.of(null, alias.get()));
        }
        return new DomainRemovalResponse(moved, renamed, 0);
    }

    private Optional<String> moveOne(final long domainId, final DomainLink link) {
        try {
            return Optional.ofNullable(moveKeepingAFreeAlias(domainId, link));
        } catch (final RuntimeException exception) {
            log.warn("Moving a link to hrva.cc failed, it stays on the removed domain urlId={} domainId={} exception={}", link.id(), domainId,
                    exception.getClass().getName());
            return Optional.empty();
        }
    }

    private String moveKeepingAFreeAlias(final long domainId, final DomainLink link) {
        try {
            return transactionOperations.execute(status -> moveTo(domainId, link, keptOrFresh(link.shortUrl())));
        } catch (final DuplicateKeyException exception) {
            log.info("Moving a link lost its alias to a concurrent create, trying a random one urlId={} domainId={}", link.id(), domainId);
            return transactionOperations.execute(status -> moveTo(domainId, link, urlService.nextFreeShortUrl(null)));
        }
    }

    private String moveTo(final long domainId, final DomainLink link, final String alias) {
        if (!urlDomainMoves.moveToPrimary(link.id(), domainId, alias)) {
            log.info("Link left the removed domain before it could be moved urlId={} domainId={}", link.id(), domainId);
            return null;
        }
        return alias;
    }

    private String keptOrFresh(final String alias) {
        if (urlRepository.aliasTaken(null, alias) || reservedAliasService.isReserved(alias)) {
            return urlService.nextFreeShortUrl(null);
        }
        return alias;
    }

    public record Actor(String performedBy, boolean admin) {
    }

    private record Marked(long domainId, CustomDomainStatus from, List<DomainLink> links, int deactivated) {
    }

}
