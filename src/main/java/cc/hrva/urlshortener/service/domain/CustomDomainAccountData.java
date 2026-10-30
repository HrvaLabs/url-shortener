package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.dto.DataExportDto.DomainExport;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import com.google.common.collect.Iterables;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainAccountData {

    private static final int IDS_PER_QUERY = 1_000;

    private final CustomDomainRepository customDomainRepository;
    private final AccountLock accountLock;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public List<DomainExport> exportFor(final User user) {
        return customDomainRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(user.getId()).stream()
                .map(CustomDomainAccountData::export)
                .toList();
    }

    public List<Long> releaseFor(final User user) {
        final var owner = accountLock.lock(user);
        final var applications = customDomainRepository.deleteApplicationsOfOwner(owner.getId());
        final var released = customDomainRepository.findIdsOfOwner(owner.getId());
        if (!released.isEmpty()) {
            final var now = LocalDateTime.now(clock);
            customDomainRepository.releaseDomainsAsDeleted(owner.getId(), now);
            customDomainRepository.releaseRemovedDomains(owner.getId(), now);
            released.forEach(id -> eventPublisher.publishEvent(new CustomDomainChangedEvent(id)));
        }
        if (applications > 0 || !released.isEmpty()) {
            log.info("Custom domains released with their account userId={} applicationsDeleted={} domainIds={}", owner.getId(), applications,
                    released);
        }
        return released;
    }

    public void purgeFor(final Collection<Long> domainIds) {
        if (domainIds.isEmpty()) {
            return;
        }
        var purged = 0;
        for (final var chunk : Iterables.partition(domainIds, IDS_PER_QUERY)) {
            purged += customDomainRepository.deleteReleased(chunk);
        }
        log.info("Custom domains of a deleted account removed released={} removed={}", domainIds.size(), purged);
    }

    private static DomainExport export(final CustomDomain domain) {
        return new DomainExport(
                domain.getHostname(),
                domain.getStatus(),
                domain.getReason(),
                domain.getReviewNote(),
                domain.getCreatedAt(),
                domain.getReviewedAt(),
                domain.getActivatedAt(),
                domain.getDisabledAt(),
                domain.getDeletedAt());
    }

}
