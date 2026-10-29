package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.CreatedInRange;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.LinkInventory;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.NewestLink;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.CreatedVia;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.stats.HourlyCountRepository;
import cc.hrva.urlshortener.repository.stats.HourlySeries;
import cc.hrva.urlshortener.repository.stats.LinksStatsRepository;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LinksStats {

    private static final int TOP_LIMIT = 10;
    private static final String UNKNOWN_CHANNEL = "unknown";
    private static final List<String> CHANNEL_KEYS =
            Stream.concat(Arrays.stream(CreatedVia.values()).map(CreatedVia::name), Stream.of(UNKNOWN_CHANNEL)).toList();

    private final LinksStatsRepository linksStatsRepository;
    private final HourlyCountRepository hourlyCountRepository;
    private final DailyBuckets dailyBuckets;
    private final UrlRepository urlRepository;
    private final StatsDays statsDays;

    public LinksStatsResponse collect(final StatsWindow window) {
        final var hourly = hourlyCountRepository.count(HourlySeries.LINKS_BY_CHANNEL, window.start(), window.end());
        final var created = linksStatsRepository.countCreated(window.start(), window.end());
        final var inventory = linksStatsRepository.countInventory(statsDays.storedNow());
        final var newest = urlRepository.findTop10ByOrderByCreateDateDescIdDesc().stream()
                .map(LinksStats::newestLinkOf)
                .toList();

        return new LinksStatsResponse(
                window.days(),
                window.from(),
                window.to(),
                dailyBuckets.stack(hourly, CHANNEL_KEYS, window),
                new CreatedInRange(created.total(), created.total() - created.anonymous(), created.anonymous(), created.aiTagged()),
                new LinkInventory(
                        inventory.total(),
                        inventory.active(),
                        inventory.total() - inventory.active(),
                        inventory.expired(),
                        inventory.passwordProtected(),
                        inventory.withVisitLimit(),
                        inventory.withExpiry(),
                        linksStatsRepository.countBlockedByReason()),
                linksStatsRepository.findTopTags(TOP_LIMIT),
                linksStatsRepository.findTopDomains(TOP_LIMIT),
                newest,
                linksStatsRepository.countDeadDestinations());
    }

    private static NewestLink newestLinkOf(final Url url) {
        final var link = UrlResponse.from(url);
        return new NewestLink(
                link.id(),
                link.shortUrl(),
                DomainNames.hostOf(link.longUrl()).orElse(""),
                link.ownerEmail(),
                Optional.ofNullable(url.getCreatedVia()).map(CreatedVia::name).orElse(UNKNOWN_CHANNEL),
                link.createDate(),
                link.status());
    }

}
