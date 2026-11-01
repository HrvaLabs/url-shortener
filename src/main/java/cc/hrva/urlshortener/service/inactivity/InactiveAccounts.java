package cc.hrva.urlshortener.service.inactivity;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppDto;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.SendingEmailService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InactiveAccounts {

    private static final long WARNING_DAYS_AHEAD = 30;

    private final UserRepository userRepository;
    private final ConnectedAppsService connectedAppsService;
    private final SendingEmailService sendingEmailService;
    private final AppProperties appProperties;
    private final Clock clock;

    public List<User> warnAndSelectForDeactivation() {
        final var now = LocalDateTime.now(clock);
        final var inactiveDays = appProperties.getDeactivateUserAccountAfterDays();
        final var deactivateBefore = now.minusDays(inactiveDays);
        final var warnBefore = now.minusDays(Math.max(inactiveDays - WARNING_DAYS_AHEAD, 0));
        final var due = new ArrayList<User>();

        for (final var user : userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(warnBefore)) {
            final var activity = lastActivityOf(user);
            if (activity.isAfter(warnBefore)) {
                continue;
            }
            if (!isWarnedFor(user, activity)) {
                warn(user, activity, now, inactiveDays);
            } else if (!activity.isAfter(deactivateBefore) && !user.getInactivityWarnedAt().isAfter(now.minusDays(WARNING_DAYS_AHEAD))) {
                due.add(user);
            }
        }
        return due;
    }

    private static boolean isWarnedFor(final User user, final LocalDateTime activity) {
        return user.getInactivityWarnedAt() != null && !user.getInactivityWarnedAt().isBefore(activity);
    }

    private void warn(final User user, final LocalDateTime activity, final LocalDateTime now, final long inactiveDays) {
        if (userRepository.claimInactivityWarning(user.getId(), now, activity) == 0) {
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
        return Stream.concat(Stream.of(user.getLastLogin(), user.getLastActiveAt()), mcpUse)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElseThrow();
    }

}
