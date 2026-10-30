package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.dto.domain.AdminDomainResponse;
import cc.hrva.urlshortener.dto.domain.DomainOwnerSummary;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.DomainLinkCount;
import cc.hrva.urlshortener.repository.OwnerLinkCount;
import cc.hrva.urlshortener.repository.OwnerReviewCount;
import cc.hrva.urlshortener.service.ScammerSignalService;
import com.google.common.collect.Iterables;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminDomainResponses {

    private static final int IDS_PER_QUERY = 1000;

    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainResponses customDomainResponses;
    private final ScammerSignalService scammerSignalService;
    private final Clock clock;

    public AdminDomainResponse of(final CustomDomain domain) {
        return of(List.of(domain)).getFirst();
    }

    public List<AdminDomainResponse> of(final List<CustomDomain> domains) {
        if (domains.isEmpty()) {
            return List.of();
        }
        final var owners = domains.stream()
                .map(CustomDomain::getOwner)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(User::getId, Function.identity(), (first, second) -> first, LinkedHashMap::new));
        final var ownerIds = owners.keySet();
        final var linkCounts = byId(inChunks(ownerIds, customDomainRepository::countLinksOfOwners), OwnerLinkCount::ownerId);
        final var reviewCounts = byId(inChunks(ownerIds, customDomainRepository::countOpenReviewsOfOwners), OwnerReviewCount::ownerId);
        final var domainCounts = byId(inChunks(domains.stream().map(CustomDomain::getId).collect(Collectors.toSet()),
                customDomainRepository::countLinksOfDomains), DomainLinkCount::domainId);
        final var possibleScammers = owners.isEmpty() ? Set.<Long>of() : scammerSignalService.possibleScammers(owners.values());
        return domains.stream()
                .map(domain -> responseOf(domain, linkCounts, reviewCounts, domainCounts.get(domain.getId()), possibleScammers))
                .toList();
    }

    private AdminDomainResponse responseOf(final CustomDomain domain, final Map<Long, OwnerLinkCount> linkCounts,
                                           final Map<Long, OwnerReviewCount> reviewCounts, final DomainLinkCount counts,
                                           final Set<Long> possibleScammers) {
        return new AdminDomainResponse(
                customDomainResponses.of(domain, counts == null ? 0 : counts.links(), clock),
                ownerOf(domain.getOwner(), linkCounts, reviewCounts, possibleScammers),
                domain.getApplyFlags(),
                domain.getCloudflareId(),
                domain.getCloudflareStatus(),
                domain.getCloudflareSslStatus(),
                domain.getLastError(),
                domain.getDisabledReason(),
                seconds(domain.getDisabledAt()),
                seconds(domain.getDeletedAt()),
                counts == null ? 0 : counts.clicks());
    }

    private static DomainOwnerSummary ownerOf(final User owner, final Map<Long, OwnerLinkCount> linkCounts, final Map<Long, OwnerReviewCount> reviewCounts,
                                              final Set<Long> possibleScammers) {
        if (owner == null) {
            return null;
        }
        final var links = linkCounts.get(owner.getId());
        final var reviews = reviewCounts.get(owner.getId());
        return new DomainOwnerSummary(
                owner.getId(),
                owner.getEmail(),
                !Boolean.FALSE.equals(owner.getActive()),
                seconds(owner.getCreateDate()),
                links == null ? 0 : links.links(),
                links == null ? 0 : links.blockedLinks(),
                reviews == null ? 0 : reviews.openReviews(),
                possibleScammers.contains(owner.getId()));
    }

    private static <T> List<T> inChunks(final Collection<Long> ids, final Function<Collection<Long>, List<T>> query) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return StreamSupport.stream(Iterables.partition(List.copyOf(ids), IDS_PER_QUERY).spliterator(), false)
                .map(query)
                .flatMap(List::stream)
                .toList();
    }

    private static <T> Map<Long, T> byId(final List<T> counts, final Function<T, Long> id) {
        return counts.stream().collect(Collectors.toMap(id, Function.identity(), (first, second) -> first));
    }

    private static LocalDateTime seconds(final LocalDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.SECONDS);
    }

}
