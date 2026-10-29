package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome.DeadLink;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPayloadsTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-27T01:14:00.123456Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private NotificationPayloads payloads;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc/");
        payloads = new NotificationPayloads(JsonMapper.builder().build(), appProperties, Clock.fixed(Instant.parse("2026-10-27T02:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void linkDeadBody() {
        final var json = payloads.json(NotificationType.LINK_DEAD, "uid-1", CREATED_AT,
                payloads.linkDead(List.of(new DeadLink(1L, 7L, "abc", "example.com", LocalDateTime.of(2026, 10, 26, 23, 30), "404"))));

        assertStrict(json, """
                {"event":"link.dead","deliveryId":"uid-1","createdAt":"2026-10-27T01:14:00Z",
                 "links":[{"shortUrl":"https://hrva.cc/abc","destinationHost":"example.com","deadSince":"2026-10-27","lastStatus":"404"}]}""");
        assertThat(json).startsWith("{\"event\":\"link.dead\",\"deliveryId\":\"uid-1\",\"createdAt\":\"2026-10-27T01:14:00Z\",\"links\":");
    }

    @Test
    void linkBackBody() {
        final var json = payloads.json(NotificationType.LINK_BACK, "uid-2", CREATED_AT,
                payloads.linkBack(List.of(new DeadLink(1L, 7L, "abc", "example.com", LocalDateTime.of(2026, 10, 20, 2, 11), null)), LocalDate.of(2026, 10, 27)));

        assertStrict(json, """
                {"event":"link.back","deliveryId":"uid-2","createdAt":"2026-10-27T01:14:00Z",
                 "links":[{"shortUrl":"https://hrva.cc/abc","destinationHost":"example.com","deadSince":"2026-10-20","backOn":"2026-10-27"}]}""");
    }

    @Test
    void testBody() {
        final var json = payloads.json(NotificationPayloads.TEST_EVENT, "test-0d1c", CREATED_AT, payloads.test());

        assertStrict(json, """
                {"event":"test","deliveryId":"test-0d1c","createdAt":"2026-10-27T01:14:00Z","message":"This is a test from hrva.cc. Your channel works."}""");
    }

    @Test
    void aMissingHostIsNull() {
        final var json = payloads.json(NotificationType.LINK_DEAD, "u", CREATED_AT,
                payloads.linkDead(List.of(new DeadLink(1L, 7L, "abc", null, LocalDateTime.of(2026, 10, 26, 3, 0), "nxdomain"))));

        assertThat(json).contains("\"destinationHost\":null", "\"lastStatus\":\"nxdomain\"");
    }

    @Test
    void createdAtIsAnIsoInstantWithZ() {
        final var json = payloads.json(NotificationType.LINK_DEAD, "u", Instant.parse("2026-10-27T01:14:59.999Z"), payloads.linkDead(List.of()));

        assertThat(json).contains("\"createdAt\":\"2026-10-27T01:14:59Z\"", "\"links\":[]");
    }

    private static void assertStrict(final String actual, final String expected) {
        assertThat(JSON.readTree(actual)).isEqualTo(JSON.readTree(expected));
    }

}
