package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.AnalyticsOverviewDto;
import cc.hrva.urlshortener.dto.AnalyticsOverviewDto.TopUrl;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.DailyCount;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.IPAddress;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.IPAddressRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.UserService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultAnalyticsService implements AnalyticsService {

    private static final int TOP_URLS_LIMIT = 5;
    private static final int DAILY_SERIES_DAYS = 30;
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String NO_AUTHORIZATION = "You don't have authorization for this action";

    private final UserService userService;
    private final UrlRepository urlRepository;
    private final IPAddressRepository ipAddressRepository;

    @Override
    public AnalyticsOverviewDto getOverview() {
        final var user = currentUser();
        final var urls = urlRepository.findByOwner(user);
        final var now = LocalDateTime.now();

        final var totalVisits = urls.stream().mapToLong(url -> safeLong(url.getVisits())).sum();
        final var activeUrls = urls.stream().filter(Url::isActive).count();
        final var expiredUrls = urls.stream()
                .filter(url -> url.getExpirationDate() != null && url.getExpirationDate().isBefore(now))
                .count();

        final var topUrls = urls.stream()
                .sorted(Comparator.comparingLong((Url url) -> safeLong(url.getVisits())).reversed())
                .limit(TOP_URLS_LIMIT)
                .map(url -> new TopUrl(url.getId(), url.getShortUrl(), url.getLongUrl(), safeLong(url.getVisits())))
                .toList();

        return new AnalyticsOverviewDto(urls.size(), activeUrls, expiredUrls, totalVisits, topUrls);
    }

    @Override
    public UrlAnalyticsDto getUrlAnalytics(final Long urlId) {
        final var user = currentUser();
        final var url = findUrl(urlId);

        if (!isOwner(url, user) && !isAdmin(user)) {
            throw new NoAuthorizationException(NO_AUTHORIZATION);
        }

        return buildAnalytics(url);
    }

    @Override
    public UrlAnalyticsDto getUrlAnalyticsForOwner(final Long urlId) {
        final var user = currentUser();
        final var url = findUrl(urlId);

        if (!isOwner(url, user)) {
            throw new NoAuthorizationException(NO_AUTHORIZATION);
        }

        return buildAnalytics(url);
    }

    private Url findUrl(final Long urlId) {
        return urlRepository.findById(urlId)
                .orElseThrow(() -> new UrlNotFoundException("URL not found"));
    }

    private UrlAnalyticsDto buildAnalytics(final Url url) {
        final var ipAddresses = ipAddressRepository.findAllByUrl(url);
        final var uniqueRecentVisitors = ipAddresses.size();
        final var totalRecentClicks = ipAddresses.stream().mapToLong(ip -> safeLong(ip.getVisits())).sum();

        return new UrlAnalyticsDto(
                url.getId(),
                url.getShortUrl(),
                url.getLongUrl(),
                UrlResponse.from(url).status(),
                safeLong(url.getVisits()),
                uniqueRecentVisitors,
                totalRecentClicks,
                url.getLastAccessed(),
                url.getCreateDate(),
                buildDailySeries(ipAddresses));
    }

    private List<DailyCount> buildDailySeries(final List<IPAddress> ipAddresses) {
        final Map<LocalDate, Long> countsByDay = ipAddresses.stream()
                .filter(ip -> ip.getCreateDate() != null)
                .collect(Collectors.groupingBy(ip -> ip.getCreateDate().toLocalDate(), Collectors.counting()));

        final var today = LocalDate.now();
        final var firstDay = today.minusDays(DAILY_SERIES_DAYS - 1L);

        return firstDay.datesUntil(today.plusDays(1))
                .map(day -> new DailyCount(day, countsByDay.getOrDefault(day, 0L)))
                .toList();
    }

    private boolean isOwner(final Url url, final User user) {
        return url.getOwner() != null && Objects.equals(url.getOwner().getId(), user.getId());
    }

    private boolean isAdmin(final User user) {
        return user.getAuthorities().stream().anyMatch(authority -> ROLE_ADMIN.equals(authority.getName()));
    }

    private User currentUser() {
        final var user = userService.getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException("Not authenticated");
        }
        return user;
    }

    private long safeLong(final Long value) {
        return value != null ? value : 0L;
    }

}
