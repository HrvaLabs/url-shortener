package cc.hrva.urlshortener.service.analytics;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.ClickRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UniqueVisitorCounterTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);
    private static final long URL_ID = 7;
    private static final String CLIENT_IP = "203.0.113.7";
    private static final ClickRequest CLICK = ClickRequest.of(CLIENT_IP, "UA", null, "HR");
    private static final String SALT_KEY = "analytics:salt:2026-10-24";
    private static final String VISITORS_KEY = "analytics:visitors:7:2026-10-24";
    private static final String EXPIRY_MILLIS = "1792893600000";
    private static final byte[] SALT = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] THEIR_SALT = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final String SALT_BASE64 = Base64.getEncoder().encodeToString(SALT);
    private static final String THEIR_SALT_BASE64 = Base64.getEncoder().encodeToString(THEIR_SALT);
    private static final String WARNING = "Unique visitor check unavailable, counting a new visitor urlId=7 exception=";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Captor
    private ArgumentCaptor<RedisScript<Long>> script;

    @Captor
    private ArgumentCaptor<String> createdSalt;

    private UniqueVisitorCounter uniqueVisitorCounter;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        final var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        uniqueVisitorCounter = new UniqueVisitorCounter(redisTemplate, new AnalyticsDays(clock, new AppProperties()), clock);
    }

    @Test
    void firstClickOfAVisitorIsNew() {
        final var hash = UniqueVisitorCounter.visitorHash(SALT, CLICK, URL_ID);
        when(valueOperations.get(SALT_KEY)).thenReturn(SALT_BASE64);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(VISITORS_KEY)), eq(hash), eq(EXPIRY_MILLIS))).thenReturn(1L);

        assertThat(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).isTrue();

        verify(redisTemplate).execute(script.capture(), eq(List.of(VISITORS_KEY)), eq(hash), eq(EXPIRY_MILLIS));
        assertThat(script.getValue().getResultType()).isEqualTo(Long.class);
        assertThat(script.getValue().getScriptAsString())
                .contains("local added = redis.call('SADD', KEYS[1], ARGV[1])")
                .contains("redis.call('PEXPIREAT', KEYS[1], ARGV[2])")
                .contains("return added");
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void repeatClickIsNotNew() {
        when(valueOperations.get(SALT_KEY)).thenReturn(SALT_BASE64);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of(VISITORS_KEY)), eq(UniqueVisitorCounter.visitorHash(SALT, CLICK, URL_ID)), eq(EXPIRY_MILLIS)))
                .thenReturn(0L);

        assertThat(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).isFalse();
    }

    @Test
    void createsTheDaySaltOnceWithAnExpiryAtTheDayBoundary() {
        when(valueOperations.get(SALT_KEY)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(SALT_KEY), anyString(), eq(Duration.ofHours(16)))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertThat(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).isTrue();

        verify(valueOperations).setIfAbsent(eq(SALT_KEY), createdSalt.capture(), eq(Duration.ofHours(16)));
        final var salt = Base64.getDecoder().decode(createdSalt.getValue());
        assertThat(salt).hasSize(32);
        verify(valueOperations, times(1)).get(SALT_KEY);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(VISITORS_KEY)), eq(UniqueVisitorCounter.visitorHash(salt, CLICK, URL_ID)), eq(EXPIRY_MILLIS));
    }

    @Test
    void usesTheSaltAnotherClickCreatedFirst() {
        when(valueOperations.get(SALT_KEY)).thenReturn(null, THEIR_SALT_BASE64);
        when(valueOperations.setIfAbsent(eq(SALT_KEY), anyString(), eq(Duration.ofHours(16)))).thenReturn(false);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertThat(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).isTrue();

        verify(valueOperations, times(2)).get(SALT_KEY);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(VISITORS_KEY)), eq(UniqueVisitorCounter.visitorHash(THEIR_SALT, CLICK, URL_ID)), eq(EXPIRY_MILLIS));
    }

    @Test
    void neverCreatesASaltWithoutATimeToLive() {
        final var atExpiry = Clock.fixed(Instant.parse("2026-10-25T02:00:00Z"), ZoneOffset.UTC);
        final var lateCounter = new UniqueVisitorCounter(redisTemplate, new AnalyticsDays(atExpiry, new AppProperties()), atExpiry);
        when(valueOperations.get(SALT_KEY)).thenReturn(null);

        assertThat(lateCounter.isNewVisitor(URL_ID, DAY, CLICK)).isTrue();

        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void countsANewVisitorWhenRedisFails(final CapturedOutput output) {
        when(valueOperations.get(SALT_KEY)).thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        assertThat(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).isTrue();

        assertThat(output)
                .contains(WARNING + RedisConnectionFailureException.class.getName())
                .doesNotContain(CLIENT_IP);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void countsANewVisitorWhenTheScriptFails(final CapturedOutput output) {
        when(valueOperations.get(SALT_KEY)).thenReturn(SALT_BASE64);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisSystemException("OOM command not allowed when used memory > 'maxmemory'", null));

        assertThat(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).isTrue();

        assertThat(output)
                .contains(WARNING + RedisSystemException.class.getName())
                .doesNotContain(CLIENT_IP)
                .doesNotContain(SALT_BASE64)
                .doesNotContain(UniqueVisitorCounter.visitorHash(SALT, CLICK, URL_ID));
    }

    @Test
    void countsANewVisitorWhenTheScriptReturnsNothing() {
        when(valueOperations.get(SALT_KEY)).thenReturn(SALT_BASE64);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

        assertThat(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).isTrue();

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(VISITORS_KEY)), eq(UniqueVisitorCounter.visitorHash(SALT, CLICK, URL_ID)), eq(EXPIRY_MILLIS));
    }

    @Test
    void hashSeparatesLinksAndSaltsAndHidesTheIp() {
        final var hash = UniqueVisitorCounter.visitorHash(SALT, CLICK, URL_ID);

        assertThat(UniqueVisitorCounter.visitorHash(SALT, CLICK, URL_ID)).isEqualTo(hash);
        assertThat(hash)
                .isNotEqualTo(UniqueVisitorCounter.visitorHash(SALT, CLICK, 8))
                .isNotEqualTo(UniqueVisitorCounter.visitorHash(THEIR_SALT, CLICK, URL_ID))
                .hasSize(22)
                .matches("[A-Za-z0-9_-]{22}")
                .doesNotContain(CLIENT_IP);
    }

    @Test
    void hashIsTheFirstSixteenBytesOfTheHmacOfIpUserAgentAndLink() throws GeneralSecurityException {
        assertThat(UniqueVisitorCounter.visitorHash(SALT, CLICK, URL_ID)).isEqualTo(truncatedHmac(SALT, "203.0.113.7\nUA\n7"));
        assertThat(UniqueVisitorCounter.visitorHash(SALT, ClickRequest.of(null, null, "https://t.co/x", "DE"), URL_ID))
                .isEqualTo(truncatedHmac(SALT, "\n\n7"));
    }

    private static String truncatedHmac(final byte[] key, final String message) throws GeneralSecurityException {
        final var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));

        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)), 16));
    }

}
