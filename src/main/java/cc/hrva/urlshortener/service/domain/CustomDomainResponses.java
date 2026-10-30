package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.domain.CustomDomainResponse;
import cc.hrva.urlshortener.dto.domain.DnsRecord;
import cc.hrva.urlshortener.dto.domain.DomainSteps;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomDomainResponses {

    private static final Set<CustomDomainStatus> APPROVED = EnumSet.of(CustomDomainStatus.APPROVED_PROVISIONING, CustomDomainStatus.AWAITING_DNS,
            CustomDomainStatus.ACTIVE, CustomDomainStatus.DISABLED, CustomDomainStatus.ERROR);
    private static final Duration DNS_GRACE = Duration.ofMinutes(10);
    private static final String CLOUDFLARE_ACTIVE = "active";
    private static final String ACCOUNT_BLOCKED = "Turned off because the account was blocked.";
    private static final String ON_A_BLOCKLIST = "Turned off because the domain is on a blocklist.";

    private final AppProperties appProperties;

    public CustomDomainResponse of(final CustomDomain domain, final long links, final Clock clock) {
        final var approved = APPROVED.contains(domain.getStatus());
        final var problem = shownProblem(domain, LocalDateTime.now(clock));
        return new CustomDomainResponse(
                domain.getId(),
                domain.getHostname(),
                domain.getStatus(),
                domain.getReason(),
                domain.getReviewNote(),
                seconds(domain.getCreatedAt()),
                seconds(domain.getReviewedAt()),
                seconds(domain.getActivatedAt()),
                seconds(domain.getLastCheckedAt()),
                approved ? DnsRecord.cname(domain.getHostname(), cnameTarget()) : null,
                approved ? steps(domain) : null,
                problem,
                DomainProblems.text(problem),
                disabledNote(domain),
                links);
    }

    private static DomainProblem shownProblem(final CustomDomain domain, final LocalDateTime now) {
        if (domain.getProblem() != DomainProblem.DNS_NOT_FOUND) {
            return domain.getProblem();
        }
        final var reviewedAt = domain.getReviewedAt();
        return reviewedAt != null && !now.isBefore(reviewedAt.plus(DNS_GRACE)) ? DomainProblem.DNS_NOT_FOUND : null;
    }

    private static DomainSteps steps(final CustomDomain domain) {
        return new DomainSteps(CLOUDFLARE_ACTIVE.equals(domain.getCloudflareStatus()), CLOUDFLARE_ACTIVE.equals(domain.getCloudflareSslStatus()));
    }

    private static String disabledNote(final CustomDomain domain) {
        if (domain.getStatus() != CustomDomainStatus.DISABLED) {
            return null;
        }
        final var reason = domain.getDisabledReason();
        if (reason == null || reason == DomainDisableReason.ADMIN) {
            return domain.getDisabledNote();
        }
        return reason == DomainDisableReason.USER_BLOCKED ? ACCOUNT_BLOCKED : ON_A_BLOCKLIST;
    }

    private String cnameTarget() {
        final var target = appProperties.getDomains().getCnameTarget();
        return CustomDomainHostnames.hostnameKey(target).orElse(target);
    }

    private static LocalDateTime seconds(final LocalDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.SECONDS);
    }

}
