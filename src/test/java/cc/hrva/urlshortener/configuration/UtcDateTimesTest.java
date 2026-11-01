package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.dto.UrlResponse;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtcDateTimesTest {

    private static final LocalDateTime END_OF_YEAR = LocalDateTime.of(2026, 12, 31, 23, 59, 59);

    private final JsonMapper mapper = configured();

    @Test
    void writesADateTimeAsUtcWithZ() {
        assertThat(mapper.writeValueAsString(LocalDateTime.of(2026, 10, 30, 19, 2, 13))).isEqualTo("\"2026-10-30T19:02:13Z\"");
    }

    @Test
    void keepsTheFractionOfASecond() {
        assertThat(mapper.writeValueAsString(LocalDateTime.of(2026, 10, 30, 19, 2, 13, 123_456_000))).isEqualTo("\"2026-10-30T19:02:13.123456Z\"");
    }

    @Test
    void writesNullAsNull() {
        assertThat(mapper.writeValueAsString(new Holder(null))).isEqualTo("{\"at\":null}");
    }

    @Test
    void readsAnOffsetLessValueAsUtcLikeBefore() {
        assertThat(read("2026-12-31T23:59:59")).isEqualTo(END_OF_YEAR);
        assertThat(read("2026-12-31T23:59")).isEqualTo(END_OF_YEAR.withSecond(0));
    }

    @Test
    void readsZAndOffsetsAsTheSameInstantInUtc() {
        assertThat(read("2026-12-31T23:59:59Z")).isEqualTo(END_OF_YEAR);
        assertThat(read("2026-12-31T22:59:59.000Z")).isEqualTo(END_OF_YEAR.minusHours(1));
        assertThat(read("2027-01-01T00:59:59+01:00")).isEqualTo(END_OF_YEAR);
        assertThat(read("2026-12-31T18:59:59-05:00")).isEqualTo(END_OF_YEAR);
    }

    @Test
    void refusesSomethingThatIsNotADateTime() {
        assertThatThrownBy(() -> mapper.readValue("{\"at\":\"tomorrow\"}", Holder.class)).isInstanceOf(DatabindException.class);
    }

    @Test
    void aLinkCachedBeforeTheChangeStillReadsAndReadsTheSameAfterIt() {
        final var link = new UrlResponse(40L, "https://example.com", "abc", LocalDateTime.of(2026, 10, 1, 8, 0, 0, 500_000_000), END_OF_YEAR,
                3L, 10L, true, "ACTIVE", Set.of(), false, null);
        final var oldFormat = JsonMapper.builder().build().writeValueAsString(link);

        assertThat(oldFormat).contains("\"expirationDate\":\"2026-12-31T23:59:59\"");
        assertThat(mapper.readValue(oldFormat, UrlResponse.class)).isEqualTo(link);
        assertThat(mapper.readValue(mapper.writeValueAsString(link), UrlResponse.class)).isEqualTo(link);
    }

    @Test
    void aCachedMapConvertsLikeBefore() {
        final var cached = new LinkedHashMap<String, Object>(Map.of("id", 40, "longUrl", "https://example.com", "shortUrl", "abc",
                "expirationDate", "2026-12-31T23:59:59", "createDate", "2026-10-01T08:00:00Z", "active", true, "passwordProtected", false));

        final var link = mapper.convertValue(cached, UrlResponse.class);

        assertThat(link.expirationDate()).isEqualTo(END_OF_YEAR);
        assertThat(link.createDate()).isEqualTo(LocalDateTime.of(2026, 10, 1, 8, 0));
    }

    private LocalDateTime read(final String value) {
        return mapper.readValue("{\"at\":\"" + value + "\"}", Holder.class).at();
    }

    private static JsonMapper configured() {
        final var builder = JsonMapper.builder();
        new JacksonConfiguration().utcDateTimes().customize(builder);
        return builder.build();
    }

    record Holder(LocalDateTime at) {
    }

}
