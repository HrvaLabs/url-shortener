package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.AdminStatsResponse;
import cc.hrva.urlshortener.dto.AdminStatsResponse.RecentUrl;
import cc.hrva.urlshortener.dto.stats.CacheStats;
import cc.hrva.urlshortener.dto.stats.RedirectTiming;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.AdminService;
import cc.hrva.urlshortener.service.stats.AppVersion;
import cc.hrva.urlshortener.service.stats.HealthStatuses;
import cc.hrva.urlshortener.service.stats.RuntimeMetrics;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultAdminService implements AdminService {

    private static final String NO_VALUE = "—";
    private static final String UP = Status.UP.getCode();
    private static final String DATABASE = "db";
    private static final String REDIS = "redis";
    private static final long BYTES_PER_MB = 1024 * 1024;

    private final UserRepository userRepository;
    private final UrlRepository urlRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final Environment environment;
    private final RuntimeMetrics runtimeMetrics;
    private final HealthStatuses healthStatuses;
    private final AppVersion appVersion;

    @Override
    public byte[] exportAllUrlsAsCsv() {
        final var urls = urlRepository.findAll(PageRequest.of(0, Integer.MAX_VALUE));
        final var sb = new StringBuilder();
        sb.append("ID,Short URL,Long URL,Owner,Visits,Visit Limit,Created,Expires,Active\n");
        for (final var url : urls) {
            sb.append(String.format("%d,%s,%s,%s,%d,%d,%s,%s,%b\n",
                    url.getId(),
                    url.getShortUrl(),
                    escapeCsv(url.getLongUrl()),
                    url.getOwner() != null ? url.getOwner().getEmail() : "",
                    url.getVisits(),
                    url.getVisitLimit() != null ? url.getVisitLimit() : 0,
                    url.getCreateDate() != null ? url.getCreateDate().toString() : "",
                    url.getExpirationDate() != null ? url.getExpirationDate().toString() : "",
                    url.isActive()));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String escapeCsv(final String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Override
    public AdminStatsResponse getDashboardStats() {
        final var now = LocalDateTime.now();
        final var redirectTimings = runtimeMetrics.redirectTimings();
        final var redirectCount = redirectTimings.stream().mapToLong(RedirectTiming::count).sum();
        final var heap = runtimeMetrics.heap();

        return new AdminStatsResponse(
                userRepository.count(),
                userRepository.countByCreateDateAfter(now.minusDays(7)),
                userRepository.countByCreateDateAfter(now.minusDays(30)),
                urlRepository.count(),
                urlRepository.countByActiveTrue(),
                apiKeyRepository.count(),
                appVersion.current(),
                RuntimeMetrics.formatUptime(runtimeMetrics.uptime()),
                UP.equals(healthStatuses.status(REDIS)),
                UP.equals(healthStatuses.status(DATABASE)),
                System.getProperty("java.version"),
                now,
                fetchRecentUrls(),
                formatHitRatio(runtimeMetrics.urlCacheStats()),
                List.of(environment.getActiveProfiles()),
                formatMegabytes(heap.usedBytes()),
                formatMegabytes(heap.maxBytes()),
                runtimeMetrics.requestCounts().total(),
                formatRedirectAvgMs(redirectTimings, redirectCount),
                formatRedirectMaxMs(redirectTimings, redirectCount),
                redirectCount);
    }

    private List<RecentUrl> fetchRecentUrls() {
        return urlRepository.findTop10ByOrderByCreateDateDescIdDesc().stream()
                .map(url -> new RecentUrl(
                        url.getId(),
                        url.getShortUrl(),
                        url.getLongUrl(),
                        url.getCreateDate(),
                        url.getVisits(),
                        url.getOwner() != null ? url.getOwner().getEmail() : null))
                .toList();
    }

    private static String formatRedirectAvgMs(final List<RedirectTiming> redirectTimings, final long redirectCount) {
        if (redirectCount == 0) {
            return NO_VALUE;
        }
        final var totalMs = redirectTimings.stream()
                .filter(timing -> timing.meanMs() != null)
                .mapToDouble(timing -> timing.meanMs() * timing.count())
                .sum();
        return formatMs(totalMs / redirectCount);
    }

    private static String formatRedirectMaxMs(final List<RedirectTiming> redirectTimings, final long redirectCount) {
        if (redirectCount == 0) {
            return NO_VALUE;
        }
        return redirectTimings.stream()
                .map(RedirectTiming::maxMs)
                .filter(Objects::nonNull)
                .max(Double::compare)
                .map(DefaultAdminService::formatMs)
                .orElse(NO_VALUE);
    }

    private static String formatMs(final double millis) {
        return String.format(Locale.ROOT, "%.0f", millis);
    }

    private static String formatHitRatio(final CacheStats cacheStats) {
        return cacheStats.hitRatio() != null ? String.format(Locale.ROOT, "%.1f%%", cacheStats.hitRatio()) : NO_VALUE;
    }

    private static String formatMegabytes(final long bytes) {
        return String.format(Locale.ROOT, "%d MB", bytes / BYTES_PER_MB);
    }

}
