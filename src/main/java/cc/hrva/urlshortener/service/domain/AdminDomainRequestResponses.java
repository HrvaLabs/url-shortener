package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.domain.AdminDomainRequestResponse;
import cc.hrva.urlshortener.model.CustomDomainAllowance;
import cc.hrva.urlshortener.model.CustomDomainRequest;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.repository.CustomDomainAllowanceRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.OwnerDomainCount;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminDomainRequestResponses {

    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainAllowanceRepository customDomainAllowanceRepository;
    private final AdminDomainResponses adminDomainResponses;
    private final AppProperties appProperties;

    public AdminDomainRequestResponse of(final CustomDomainRequest request) {
        return of(List.of(request)).getFirst();
    }

    public List<AdminDomainRequestResponse> of(final List<CustomDomainRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        final var users = requests.stream().map(CustomDomainRequest::getUser).distinct().toList();
        final var userIds = users.stream().map(User::getId).collect(Collectors.toSet());
        final var owners = adminDomainResponses.ownersOf(users);
        final var limits = customDomainAllowanceRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(CustomDomainAllowance::getUserId, CustomDomainAllowance::getMaxDomains));
        final var used = customDomainRepository.countDomainsOfOwners(userIds, CustomDomainStatus.COUNTS_FOR_USER).stream()
                .collect(Collectors.toMap(OwnerDomainCount::ownerId, OwnerDomainCount::domains));
        final var defaultLimit = appProperties.getDomains().getPerUser();
        return requests.stream()
                .map(request -> {
                    final var userId = request.getUser().getId();
                    return new AdminDomainRequestResponse(
                            request.getId(),
                            request.getStatus(),
                            request.getRequestedTotal(),
                            request.getLimitAtRequest(),
                            limits.getOrDefault(userId, defaultLimit),
                            used.getOrDefault(userId, 0L),
                            request.getReason(),
                            request.getAdminNote(),
                            request.getReviewedBy(),
                            CustomDomainResponses.seconds(request.getReviewedAt()),
                            CustomDomainResponses.seconds(request.getCreatedAt()),
                            owners.get(userId));
                })
                .toList();
    }

}
