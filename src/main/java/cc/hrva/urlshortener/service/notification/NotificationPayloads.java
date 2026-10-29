package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.notification.DailySummary;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class NotificationPayloads {

    public static final String TEST_EVENT = "test";

    private static final TypeReference<LinkedHashMap<String, Object>> FIELDS = new TypeReference<>() {
    };

    private final JsonMapper jsonMapper;
    private final AppProperties appProperties;
    private final Clock clock;

    public record Links<T>(List<T> links) {
    }

    public record DeadLinkData(String shortUrl, String destinationHost, String deadSince, String lastStatus) {
    }

    public record BackLinkData(String shortUrl, String destinationHost, String deadSince, String backOn) {
    }

    public record TestData(String message) {
    }

    public record DailyData(String date, DailyTotals totals, List<DailyLinkData> links) {
    }

    public record DailyTotals(long clicks, long uniqueVisitors, long botClicks) {
    }

    public record DailyLinkData(String shortUrl, String destinationHost, long clicks, long uniqueVisitors, long botClicks) {
    }

    public String json(final NotificationType event, final String deliveryUid, final Instant createdAt, final Object data) {
        return json(event.wireName(), deliveryUid, createdAt, data);
    }

    public String json(final String eventName, final String deliveryUid, final Instant createdAt, final Object data) {
        final var body = new LinkedHashMap<String, Object>();
        body.put("event", eventName);
        body.put("deliveryId", deliveryUid);
        body.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(createdAt.truncatedTo(ChronoUnit.SECONDS)));
        body.putAll(jsonMapper.convertValue(data, FIELDS));
        return jsonMapper.writeValueAsString(body);
    }

    public Links<DeadLinkData> linkDead(final List<DeadLink> links) {
        return new Links<>(links.stream()
                .map(link -> new DeadLinkData(shortUrl(link.shortUrl()), link.destinationHost(), day(link.deadSince()).toString(), link.lastStatus()))
                .toList());
    }

    public Links<BackLinkData> linkBack(final List<DeadLink> links, final LocalDate backOn) {
        return new Links<>(links.stream()
                .map(link -> new BackLinkData(shortUrl(link.shortUrl()), link.destinationHost(), day(link.deadSince()).toString(), backOn.toString()))
                .toList());
    }

    public DailyData daily(final DailySummary summary) {
        return new DailyData(summary.date().toString(),
                new DailyTotals(summary.clicks(), summary.uniqueVisitors(), summary.botClicks()),
                summary.links().stream()
                        .limit(NotificationMessages.MOST_LINKS)
                        .map(link -> new DailyLinkData(shortUrl(link.shortUrl()), link.destinationHost(), link.clicks(), link.uniqueVisitors(),
                                link.botClicks()))
                        .toList());
    }

    public TestData test() {
        return new TestData(NotificationMessages.TEST_TEXT);
    }

    public String shortUrl(final String alias) {
        return StringUtils.removeEnd(StringUtils.defaultString(appProperties.getServerUrl()), "/") + "/" + alias;
    }

    private LocalDate day(final LocalDateTime stored) {
        return stored.atZone(clock.getZone()).withZoneSameInstant(appProperties.getAnalytics().getZone()).toLocalDate();
    }

}
