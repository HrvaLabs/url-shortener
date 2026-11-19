package cc.hrva.urlshortener.service.dnsfilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.notification.HostPacer;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class DefaultDnsFilterServiceTest {

    private static final String HOST = "malware.wicar.org";
    private static final String REFERENCE_ONE = "9.9.9.10";
    private static final String REFERENCE_TWO = "1.1.1.1";
    private static final DnsReply NXDOMAIN = new DnsReply(3, List.of());
    private static final DnsReply SINKHOLE = new DnsReply(0, List.of("0.0.0.0"));
    private static final DnsReply RESOLVED = new DnsReply(0, List.of("208.94.116.246"));
    private static final Map<DnsFilterProvider, String> ADDRESSES = Map.of(
            DnsFilterProvider.QUAD9, "9.9.9.9",
            DnsFilterProvider.CLOUDFLARE, "1.1.1.2",
            DnsFilterProvider.CLEANBROWSING, "185.228.168.9",
            DnsFilterProvider.CONTROL_D, "76.76.2.1");

    private final InMemoryRedis redis = new InMemoryRedis();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<String> asks = new ArrayList<>();
    private final Map<String, Function<String, CompletableFuture<DnsReply>>> script = new HashMap<>();
    private final Map<DnsFilterProvider, Boolean> enabled = new EnumMap<>(DnsFilterProvider.class);

    private StringRedisTemplate redisTemplate;
    private DefaultDnsFilterService dnsFilterService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        final var appProperties = new AppProperties();
        appProperties.getDnsFilters().setFilters(List.of(filter(DnsFilterProvider.QUAD9), filter(DnsFilterProvider.CLOUDFLARE, "0.0.0.0"),
                filter(DnsFilterProvider.CLEANBROWSING), filter(DnsFilterProvider.CONTROL_D, "0.0.0.0")));
        appProperties.getDnsFilters().setReferences(List.of(REFERENCE_ONE, REFERENCE_TWO));
        for (final var provider : DnsFilterProvider.values()) {
            enabled.put(provider, true);
            script.put(ADDRESSES.get(provider), host -> CompletableFuture.completedFuture(host.equals(HOST) ? blockFor(provider) : RESOLVED));
        }
        script.put(REFERENCE_ONE, host -> CompletableFuture.completedFuture(RESOLVED));
        script.put(REFERENCE_TWO, host -> CompletableFuture.completedFuture(RESOLVED));
        final var settingsService = mock(SettingsService.class);
        when(settingsService.isOn(any(SettingKey.class))).thenAnswer(invocation -> enabled.getOrDefault(providerOf(invocation.getArgument(0)), true));
        final DnsQuery dnsQuery = (address, host, timeout) -> {
            asks.add(address + " " + host);
            return script.get(address).apply(host);
        };
        final HostPacer.Sleeper sleeper = sleeps::add;
        dnsFilterService = new DefaultDnsFilterService(appProperties, settingsService, dnsQuery, redisTemplate, JsonMapper.builder().build(), clock, sleeper);
    }

    @Test
    void asksEveryEnabledFilterAndBothReferencesOnce() {
        final var report = dnsFilterService.lookup(HOST);

        assertThat(asks).hasSize(6).contains(REFERENCE_ONE + " " + HOST, REFERENCE_TWO + " " + HOST);
        assertThat(report.exists()).isTrue();
        assertThat(report.answers()).extracting(DnsFilterAnswer::provider).containsExactly(DnsFilterProvider.values());
        assertThat(report.blocking()).containsExactly(DnsFilterProvider.values());
        assertThat(report.anyUnanswered()).isFalse();
    }

    @Test
    void aSecondLookupWithinTheHourIsServedFromRedis() {
        final var first = dnsFilterService.lookup(HOST);
        asks.clear();

        final var second = dnsFilterService.lookup(HOST);

        assertThat(asks).isEmpty();
        assertThat(second).isEqualTo(first);
        assertThat(redisTemplate.getExpire("dnsfilter:" + HOST, TimeUnit.SECONDS)).isEqualTo(3600L);

        redis.expireKeysContaining("dnsfilter:");
        clock.advance(Duration.ofMinutes(61));
        dnsFilterService.lookup(HOST);

        assertThat(asks).hasSize(6);
    }

    @Test
    void lookupsWaitOneSecondBetweenHosts() {
        dnsFilterService.lookup(HOST);
        assertThat(sleeps).isEmpty();

        dnsFilterService.lookup("example.com");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));

        dnsFilterService.lookup(HOST);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aDisabledFilterIsNotAskedOrListed() {
        enabled.put(DnsFilterProvider.CLEANBROWSING, false);

        final var report = dnsFilterService.lookup(HOST);

        assertThat(asks).hasSize(5).noneMatch(ask -> ask.startsWith(ADDRESSES.get(DnsFilterProvider.CLEANBROWSING)));
        assertThat(report.answers()).extracting(DnsFilterAnswer::provider)
                .containsExactly(DnsFilterProvider.QUAD9, DnsFilterProvider.CLOUDFLARE, DnsFilterProvider.CONTROL_D);
    }

    @Test
    void aCachedReportIsNotServedWhenAFilterHasBeenEnabledSince() {
        enabled.put(DnsFilterProvider.CLEANBROWSING, false);
        dnsFilterService.lookup(HOST);
        asks.clear();
        enabled.put(DnsFilterProvider.CLEANBROWSING, true);

        final var report = dnsFilterService.lookup(HOST);

        assertThat(asks).hasSize(6);
        assertThat(report.answers()).hasSize(4);
    }

    @Test
    void anUnansweredFilterIsNeitherBlockedNorCached() {
        script.put(ADDRESSES.get(DnsFilterProvider.CLOUDFLARE), host -> CompletableFuture.failedFuture(new IllegalStateException("timed out")));

        final var report = dnsFilterService.lookup(HOST);

        assertThat(report.anyUnanswered()).isTrue();
        assertThat(report.answers()).filteredOn(answer -> answer.provider() == DnsFilterProvider.CLOUDFLARE)
                .extracting(DnsFilterAnswer::status).containsExactly(DnsFilterStatus.NO_ANSWER);
        assertThat(report.blocking()).doesNotContain(DnsFilterProvider.CLOUDFLARE);
        asks.clear();

        dnsFilterService.lookup(HOST);

        assertThat(asks).hasSize(6);
    }

    @Test
    void aFilterThatNeverAnswersCountsAsNoAnswer() {
        script.put(ADDRESSES.get(DnsFilterProvider.QUAD9), host -> new CompletableFuture<>());
        final var started = System.nanoTime();

        final var report = dnsFilterService.lookup(HOST);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(report.anyUnanswered()).isTrue();
    }

    @Test
    void bothReferencesFailingMeansTheHostDoesNotExist() {
        script.put(REFERENCE_ONE, host -> CompletableFuture.failedFuture(new IllegalStateException("down")));
        script.put(REFERENCE_TWO, host -> CompletableFuture.failedFuture(new IllegalStateException("down")));
        for (final var provider : DnsFilterProvider.values()) {
            script.put(ADDRESSES.get(provider), host -> CompletableFuture.completedFuture(NXDOMAIN));
        }

        final var report = dnsFilterService.lookup(HOST);

        assertThat(report.exists()).isFalse();
        assertThat(report.blocking()).isEmpty();
        asks.clear();

        dnsFilterService.lookup(HOST);

        assertThat(asks).hasSize(6);
    }

    @Test
    void hostsAreAskedInAsciiAndShareOneCacheEntry() {
        dnsFilterService.lookup("Čokolada.HR.");

        assertThat(asks).hasSize(6).allMatch(ask -> ask.endsWith(" xn--okolada-i6a.hr"));
        asks.clear();

        final var report = dnsFilterService.lookup("čokolada.hr");

        assertThat(asks).isEmpty();
        assertThat(report.host()).isEqualTo("xn--okolada-i6a.hr");
    }

    @Test
    void aRedisFailureStillLooksUp(final CapturedOutput output) {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("redis is down"));

        final var report = dnsFilterService.lookup(HOST);

        assertThat(asks).hasSize(6);
        assertThat(report.blocking()).hasSize(4);
        assertThat(output.getOut().lines().filter(line -> line.contains("WARN"))).hasSize(1)
                .allMatch(line -> line.contains("RedisConnectionFailureException"));
    }

    private static DnsReply blockFor(final DnsFilterProvider provider) {
        return provider == DnsFilterProvider.CLOUDFLARE || provider == DnsFilterProvider.CONTROL_D ? SINKHOLE : NXDOMAIN;
    }

    private static DnsFilterProvider providerOf(final SettingKey key) {
        return Arrays.stream(DnsFilterProvider.values()).filter(provider -> provider.settingKey() == key).findFirst().orElse(null);
    }

    private static AppProperties.DnsFilters.Filter filter(final DnsFilterProvider provider, final String... blockAnswers) {
        final var filter = new AppProperties.DnsFilters.Filter();
        filter.setProvider(provider);
        filter.setAddress(ADDRESSES.get(provider));
        filter.setBlockAnswers(List.of(blockAnswers));
        return filter;
    }

}
