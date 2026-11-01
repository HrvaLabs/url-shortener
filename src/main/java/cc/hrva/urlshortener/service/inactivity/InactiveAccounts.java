package cc.hrva.urlshortener.service.inactivity;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppDto;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.SendingEmailService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class InactiveAccounts {

    private static final long WARNING_DAYS_AHEAD = 30;
    private static final int CANDIDATES_PER_PAGE = 100;

    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ConnectedAppsService connectedAppsService;
    private final SendingEmailService sendingEmailService;
    private final AppProperties appProperties;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    public List<User> warnAndSelectForDeactivation() {
        final var now = LocalDateTime.now(clock);
        final var inactiveDays = appProperties.getDeactivateUserAccountAfterDays();
        final var deactivateBefore = now.minusDays(inactiveDays);
        final var warnBefore = now.minusDays(Math.max(inactiveDays - WARNING_DAYS_AHEAD, 0));
        final var due = new ArrayList<User>();

        var afterId = 0L;
        List<User> page;
        do {
            page = userRepository.findByLastLoginIsLessThanEqualAndActiveTrueAndIdGreaterThanOrderByIdAsc(warnBefore, afterId,
                    Limit.of(CANDIDATES_PER_PAGE));
            for (final var user : page) {
                try {
                    check(user, now, inactiveDays, warnBefore, deactivateBefore).ifPresent(due::add);
                } catch (final RuntimeException exception) {
                    log.warn("Inactivity check skipped user id={} exception={}", user.getId(), exception.getClass().getName());
                }
            }
            afterId = page.isEmpty() ? afterId : page.getLast().getId();
        } while (page.size() == CANDIDATES_PER_PAGE);
        return due;
    }

    private Optional<User> check(final User user, final LocalDateTime now, final long inactiveDays, final LocalDateTime warnBefore,
                                 final LocalDateTime deactivateBefore) {
        final var activity = lastActivityOf(user);
        if (activity.isAfter(warnBefore)) {
            return Optional.empty();
        }
        if (!isWarnedFor(user, activity)) {
            warn(user, activity, now, inactiveDays);
            return Optional.empty();
        }
        if (!activity.isAfter(deactivateBefore) && !user.getInactivityWarnedAt().isAfter(now.minusDays(WARNING_DAYS_AHEAD))) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    private static boolean isWarnedFor(final User user, final LocalDateTime activity) {
        return user.getInactivityWarnedAt() != null && !user.getInactivityWarnedAt().isBefore(activity);
    }

    private void warn(final User user, final LocalDateTime activity, final LocalDateTime now, final long inactiveDays) {
        final var claims = new TransactionTemplate(transactionManager);
        claims.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        if (!Boolean.TRUE.equals(claims.execute(status -> userRepository.claimInactivityWarning(user.getId(), now, activity) > 0))) {
            return;
        }
        final var earliest = Stream.of(activity.plusDays(inactiveDays), now.plusDays(WARNING_DAYS_AHEAD)).max(LocalDateTime::compareTo).orElseThrow();
        sendingEmailService.sendInactivityWarningEmail(user, earliest.toLocalDate());
        log.info("Warned user id={} about deactivation on {}", user.getId(), earliest.toLocalDate());
    }

    private LocalDateTime lastActivityOf(final User user) {
        final var mcpUse = connectedAppsService.findConnectedApps(user.getEmail()).stream()
                .map(ConnectedAppDto::lastUsed)
                .filter(Objects::nonNull)
                .map(lastUsed -> LocalDateTime.ofInstant(lastUsed, clock.getZone()));
        return Stream.concat(Stream.of(user.getLastLogin(), apiKeyRepository.findLastUseByOwnerId(user.getId()).orElse(null)), mcpUse)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElseThrow();
    }

}
