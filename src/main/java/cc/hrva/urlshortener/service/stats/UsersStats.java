package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.stats.UsersStatsResponse;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.UserInventory;
import cc.hrva.urlshortener.repository.stats.HourlyCountRepository;
import cc.hrva.urlshortener.repository.stats.HourlySeries;
import cc.hrva.urlshortener.repository.stats.UsersStatsRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UsersStats {

    private static final List<String> PROVIDER_KEYS = List.of("local", "google", "github");
    private static final int LAST_7_DAYS = 7;
    private static final int LAST_30_DAYS = 30;

    private final UsersStatsRepository usersStatsRepository;
    private final HourlyCountRepository hourlyCountRepository;
    private final DailyBuckets dailyBuckets;
    private final StatsDays statsDays;

    public UsersStatsResponse collect(final StatsWindow window) {
        final var hourly = hourlyCountRepository.count(HourlySeries.SIGNUPS_BY_PROVIDER, window.start(), window.end());
        final var accounts = usersStatsRepository.countAccounts();
        final var now = statsDays.storedNow();

        return new UsersStatsResponse(
                window.days(),
                window.from(),
                window.to(),
                dailyBuckets.stack(hourly, PROVIDER_KEYS, window),
                new UserInventory(
                        accounts.total(),
                        accounts.active(),
                        accounts.total() - accounts.active(),
                        usersStatsRepository.countAdmins(),
                        accounts.emailVerified(),
                        accounts.twoFactorEnabled(),
                        usersStatsRepository.countByProvider()),
                usersStatsRepository.countSignedInSince(now.minusDays(LAST_7_DAYS)),
                usersStatsRepository.countSignedInSince(now.minusDays(LAST_30_DAYS)),
                usersStatsRepository.countApiKeys(now.toLocalDate()),
                usersStatsRepository.countMcp(now),
                usersStatsRepository.countBioPages());
    }

}
