package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.AnalyticsOverviewDto;
import cc.hrva.urlshortener.dto.AnalyticsOverviewDto.TopLink;
import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.dto.LinkClicks;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.LinkSummary;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AnalyticsRange;
import cc.hrva.urlshortener.model.enums.StatDimension;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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

    private static final int TOP_LINKS_LIMIT = 10;
    private static final int TOP_VALUES_LIMIT = 10;
    private static final String OTHER = "other";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String NO_AUTHORIZATION = "You don't have authorization for this action";

    private final UserService userService;
    private final UrlRepository urlRepository;
    private final LinkStatsRepository linkStatsRepository;
    private final AnalyticsDays analyticsDays;

    @Override
    public AnalyticsOverviewDto getOverview(final Integer days) {
        final var range = AnalyticsRange.fromDays(days);
        final var user = currentUser();
        final var urls = urlRepository.findByOwner(user);
        final var now = LocalDateTime.now();
        final var to = analyticsDays.today();
        final var from = firstDayOf(range, to);
        final var stored = linkStatsRepository.findOwnerDaily(user.getId(), from, to);

        final var urlsById = urls.stream().collect(Collectors.toMap(Url::getId, Function.identity()));
        final var topLinks = linkStatsRepository.findOwnerTopLinks(user.getId(), from, to, TOP_LINKS_LIMIT).stream()
                .filter(link -> urlsById.containsKey(link.urlId()))
                .map(link -> topLinkOf(urlsById.get(link.urlId()), link))
                .toList();

        final var totalVisits = urls.stream().mapToLong(url -> safeLong(url.getVisits())).sum();
        final var activeUrls = urls.stream().filter(Url::isActive).count();
        final var expiredUrls = urls.stream()
                .filter(url -> url.getExpirationDate() != null && url.getExpirationDate().isBefore(now))
                .count();

        return new AnalyticsOverviewDto(
                range.days(),
                from,
                to,
                totalsOf(stored),
                zeroFilled(stored, from, to),
                topLinks,
                urls.size(),
                activeUrls,
                expiredUrls,
                totalVisits);
    }

    @Override
    public UrlAnalyticsDto getUrlAnalytics(final Long urlId, final Integer days) {
        final var range = AnalyticsRange.fromDays(days);
        final var user = currentUser();
        final var url = findUrl(urlId);

        if (!isOwner(url, user) && !isAdmin(user)) {
            throw new NoAuthorizationException(NO_AUTHORIZATION);
        }

        return buildAnalytics(url, range);
    }

    @Override
    public UrlAnalyticsDto getUrlAnalyticsForOwner(final Long urlId, final Integer days) {
        final var range = AnalyticsRange.fromDays(days);
        final var user = currentUser();
        final var url = findUrl(urlId);

        if (!isOwner(url, user)) {
            throw new NoAuthorizationException(NO_AUTHORIZATION);
        }

        return buildAnalytics(url, range);
    }

    private Url findUrl(final Long urlId) {
        return urlRepository.findById(urlId)
                .orElseThrow(() -> new UrlNotFoundException("URL not found"));
    }

    private UrlAnalyticsDto buildAnalytics(final Url url, final AnalyticsRange range) {
        final var to = analyticsDays.today();
        final var from = firstDayOf(range, to);
        final var stored = linkStatsRepository.findDaily(url.getId(), from, to);
        final var breakdowns = linkStatsRepository.findBreakdownTotals(url.getId(), from, to);

        return new UrlAnalyticsDto(
                summaryOf(url),
                range.days(),
                from,
                to,
                totalsOf(stored),
                zeroFilled(stored, from, to),
                new Breakdowns(
                        topValues(breakdowns, StatDimension.REFERRER),
                        topValues(breakdowns, StatDimension.COUNTRY),
                        topValues(breakdowns, StatDimension.DEVICE),
                        topValues(breakdowns, StatDimension.BROWSER),
                        topValues(breakdowns, StatDimension.OS)));
    }

    private LinkSummary summaryOf(final Url url) {
        return new LinkSummary(
                url.getId(),
                url.getShortUrl(),
                destinationHostOf(url),
                UrlResponse.from(url).status(),
                url.getPasswordHash() != null,
                url.getCreateDate(),
                url.getLastAccessed(),
                safeLong(url.getVisits()));
    }

    private static TopLink topLinkOf(final Url url, final LinkClicks link) {
        return new TopLink(url.getId(), url.getShortUrl(), destinationHostOf(url), link.clicks(), link.uniqueVisitors());
    }

    private static String destinationHostOf(final Url url) {
        return DomainNames.hostOf(url.getLongUrl()).orElse("");
    }

    private static LocalDate firstDayOf(final AnalyticsRange range, final LocalDate to) {
        return to.minusDays(range.days() - 1L);
    }

    private static AnalyticsTotals totalsOf(final List<AnalyticsDay> stored) {
        return new AnalyticsTotals(
                stored.stream().mapToLong(AnalyticsDay::clicks).sum(),
                stored.stream().mapToLong(AnalyticsDay::uniqueVisitors).sum(),
                stored.stream().mapToLong(AnalyticsDay::botClicks).sum());
    }

    private static List<AnalyticsDay> zeroFilled(final List<AnalyticsDay> stored, final LocalDate from, final LocalDate to) {
        final var storedByDate = stored.stream()
                .collect(Collectors.toMap(AnalyticsDay::date, Function.identity()));

        return from.datesUntil(to.plusDays(1))
                .map(date -> storedByDate.getOrDefault(date, new AnalyticsDay(date, 0, 0, 0)))
                .toList();
    }

    private static List<BreakdownEntry> topValues(final Map<StatDimension, List<BreakdownEntry>> breakdowns, final StatDimension dimension) {
        final var entries = breakdowns.getOrDefault(dimension, List.of());
        final var named = entries.stream().filter(entry -> !OTHER.equals(entry.value())).toList();
        final var storedOther = entries.stream().filter(entry -> OTHER.equals(entry.value())).mapToLong(BreakdownEntry::clicks).sum();
        final var leftover = named.stream().skip(TOP_VALUES_LIMIT).mapToLong(BreakdownEntry::clicks).sum();

        final var top = new ArrayList<>(named.stream().limit(TOP_VALUES_LIMIT).toList());
        if (storedOther + leftover > 0) {
            top.add(new BreakdownEntry(OTHER, storedOther + leftover));
        }
        return List.copyOf(top);
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
