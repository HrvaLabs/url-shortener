package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public final class DomainCheckSchedule {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofDays(1);
    private static final Duration DNS_DEADLINE = Duration.ofDays(14);
    private static final Duration RETRY_WINDOW = Duration.ofHours(24);
    private static final List<Duration> FIRST_RETRIES = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));

    private DomainCheckSchedule() {
    }

    public static LocalDateTime nextCheck(final CustomDomainStatus status, final DomainProblem problem, final LocalDateTime approvedAt,
                                          final int checkCount, final LocalDateTime now) {
        final var since = Duration.between(Objects.requireNonNullElse(approvedAt, now), now);
        return switch (status) {
            case APPROVED_PROVISIONING -> since.compareTo(RETRY_WINDOW) >= 0 ? null : now.plus(retryWait(checkCount));
            case AWAITING_DNS -> awaitingDns(problem, since, now);
            case ACTIVE -> now.plus(DAY);
            case ERROR -> problem != null && problem.recheckable() ? now.plus(HOUR) : null;
            case PENDING_REVIEW, REJECTED, DISABLED, DELETED -> null;
        };
    }

    private static LocalDateTime awaitingDns(final DomainProblem problem, final Duration since, final LocalDateTime now) {
        if (since.compareTo(DNS_DEADLINE) >= 0) {
            return problem == DomainProblem.DNS_NOT_FOUND ? null : now.plus(HOUR);
        }
        if (since.compareTo(HOUR) < 0) {
            return now.plus(MINUTE);
        }
        return now.plus(since.compareTo(DAY) < 0 ? TEN_MINUTES : HOUR);
    }

    private static Duration retryWait(final int attempts) {
        return attempts <= FIRST_RETRIES.size() ? FIRST_RETRIES.get(Math.max(attempts, 1) - 1) : HOUR;
    }

}
