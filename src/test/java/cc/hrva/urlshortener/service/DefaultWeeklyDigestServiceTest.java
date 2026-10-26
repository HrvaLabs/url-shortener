package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.DiscordMessage;
import cc.hrva.urlshortener.client.DiscordWebhookClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
import cc.hrva.urlshortener.dto.WeeklyDigest;
import cc.hrva.urlshortener.dto.WeeklyDigest.Change;
import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.DailyCounts;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.CreatedInRange;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.LinkInventory;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse.DomainRuleCounts;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse.ResolveTimes;
import cc.hrva.urlshortener.dto.stats.StackedSeries;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.ApiKeyCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.McpCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.UserInventory;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.service.alert.DiscordAlertFormatter;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.impl.DefaultWeeklyDigestService;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultWeeklyDigestServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    private static final LocalDate WEEK_FROM = LocalDate.of(2026, 10, 19);
    private static final LocalDate WEEK_TO = LocalDate.of(2026, 10, 25);
    private static final LocalDate BEFORE_FROM = LocalDate.of(2026, 10, 12);
    private static final LocalDate BEFORE_TO = LocalDate.of(2026, 10, 18);
    private static final List<String> CHANNELS = List.of("WEB", "API_KEY", "BULK", "MCP", "ANONYMOUS_WEB", "ANONYMOUS_MCP", "unknown");
    private static final List<String> PROVIDERS = List.of("local", "google", "github");
    private static final List<String> REVIEW_SOURCES = List.of("SYSTEM", "VISITOR");
    private static final List<String> BLOCK_REASONS =
            List.of("SAFE_BROWSING", "BLOCKLIST", "DOMAIN_RULE", "ADMIN", "USER_BLOCKED", "SCAM_SIGNALS");
    private static final StackedSeries NO_SERIES = new StackedSeries(List.of(), List.of());
    private static final String WEBHOOK = "https://discord.test/api/webhooks/1/webhook-secret";
    private static final Duration DISCORD_TIMEOUT = Duration.ofSeconds(5);
    private static final Instant NOW = Instant.parse("2026-10-26T07:00:00Z");
    private static final String WEEK_KEY = "digest:weekly:2026-10-19";

    @Mock
    private AdminStatsService adminStatsService;

    @Mock
    private AnalyticsDays analyticsDays;

    @Mock
    private DiscordWebhookClient discordWebhookClient;

    @Mock
    private SendingEmailService sendingEmailService;

    private final InMemoryRedis redis = new InMemoryRedis();
    private final AppProperties appProperties = new AppProperties();
    private StringRedisTemplate redisTemplate;
    private DiscordAlertFormatter formatter;
    private WeeklyDigestService weeklyDigestService;

    @BeforeEach
    void setUp() {
        appProperties.setFrontendUrl("https://app.hrva.cc");
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setDiscordWebhookUrl(" " + WEBHOOK + " ");
        redisTemplate = mock(StringRedisTemplate.class);
        redis.backing(redisTemplate);
        formatter = new DiscordAlertFormatter(appProperties);
        this.weeklyDigestService = service();
    }

    @Test
    void reportsTheLastClosedWeekAndTheOneBefore() {
        stubWeek(traffic(WEEK_FROM, WEEK_TO, 0, 0, 0, List.of()), links(WEEK_FROM, WEEK_TO, NO_SERIES, 0),
                users(WEEK_FROM, WEEK_TO, NO_SERIES, 0), safety(NO_SERIES, List.of(), NO_SERIES));
        stubWeekBefore(traffic(BEFORE_FROM, BEFORE_TO, 0, 0, 0, List.of()), links(BEFORE_FROM, BEFORE_TO, NO_SERIES, 0),
                users(BEFORE_FROM, BEFORE_TO, NO_SERIES, 0));

        final var digest = weeklyDigestService.collect(TODAY);

        assertThat(digest.from()).isEqualTo(WEEK_FROM);
        assertThat(digest.to()).isEqualTo(WEEK_TO);
        verify(adminStatsService).traffic(WEEK_FROM, WEEK_TO);
        verify(adminStatsService).links(WEEK_FROM, WEEK_TO);
        verify(adminStatsService).users(WEEK_FROM, WEEK_TO);
        verify(adminStatsService).safety(WEEK_FROM, WEEK_TO);
        verify(adminStatsService).traffic(BEFORE_FROM, BEFORE_TO);
        verify(adminStatsService).links(BEFORE_FROM, BEFORE_TO);
        verify(adminStatsService).users(BEFORE_FROM, BEFORE_TO);
        verifyNoMoreInteractions(adminStatsService);
    }

    @Test
    void buildsEveryFigureFromTheAdminStats() {
        stubWeek(
                traffic(WEEK_FROM, WEEK_TO, 1204, 950, 301, List.of(topLink(1L, "menu", "example.com", 120))),
                links(WEEK_FROM, WEEK_TO, series(CHANNELS, new long[] {20, 2, 0, 1, 0, 0, 0}, new long[] {10, 4, 0, 3, 0, 0, 0}), 40),
                users(WEEK_FROM, WEEK_TO, series(PROVIDERS, new long[] {2, 0, 0}, new long[] {0, 1, 0}), 12),
                safety(
                        series(REVIEW_SOURCES, new long[] {3, 1}, new long[] {0, 1}),
                        List.of(new CountEntry("OK", 3), new CountEntry("BLOCK_LINK", 1)),
                        series(BLOCK_REASONS, new long[] {0, 2, 0, 0, 0, 0}, new long[] {1, 0, 0, 0, 0, 0})));
        stubWeekBefore(
                traffic(BEFORE_FROM, BEFORE_TO, 1075, 900, 10, List.of()),
                links(BEFORE_FROM, BEFORE_TO, series(CHANNELS, new long[] {42, 0, 0, 0, 0, 0, 0}), 42),
                users(BEFORE_FROM, BEFORE_TO, series(PROVIDERS, new long[] {0, 0, 0}), 3));

        assertThat(weeklyDigestService.collect(TODAY)).isEqualTo(new WeeklyDigest(
                WEEK_FROM,
                WEEK_TO,
                new Change(1204, 1075),
                950,
                301,
                new Change(40, 42),
                List.of(new CountEntry("WEB", 30), new CountEntry("API_KEY", 6), new CountEntry("MCP", 4)),
                new Change(3, 0),
                12,
                5,
                4,
                List.of(new CountEntry("BLOCKLIST", 2), new CountEntry("SAFE_BROWSING", 1)),
                List.of(new WeeklyDigest.TopLink("menu", "example.com", 120))));
    }

    @Test
    void onlyAutomaticBlocksAreCounted() {
        stubEmptyWeekWith(
                links(WEEK_FROM, WEEK_TO, NO_SERIES, 0),
                safety(NO_SERIES, List.of(), series(BLOCK_REASONS, new long[] {0, 1, 0, 3, 2, 0}, new long[] {1, 1, 0, 1, 0, 0})),
                List.of());

        assertThat(weeklyDigestService.collect(TODAY).automaticBlocks())
                .containsExactly(new CountEntry("BLOCKLIST", 2), new CountEntry("SAFE_BROWSING", 1));
    }

    @Test
    void channelsWithoutLinksAreLeftOut() {
        stubEmptyWeekWith(
                links(WEEK_FROM, WEEK_TO, series(CHANNELS, new long[] {1, 0, 2, 2, 0, 0, 0}, new long[] {2, 0, 1, 3, 0, 0, 0}), 11),
                safety(NO_SERIES, List.of(), NO_SERIES),
                List.of());

        assertThat(weeklyDigestService.collect(TODAY).linksByChannel())
                .containsExactly(new CountEntry("MCP", 5), new CountEntry("WEB", 3), new CountEntry("BULK", 3));
    }

    @Test
    void topLinksAreTheFiveBusiestWithoutOwners() {
        final var busiest = LongStream.rangeClosed(1, 7)
                .mapToObj(rank -> topLink(rank, "link" + rank, "site" + rank + ".example", 100 - rank))
                .toList();
        stubEmptyWeekWith(links(WEEK_FROM, WEEK_TO, NO_SERIES, 0), safety(NO_SERIES, List.of(), NO_SERIES), busiest);

        assertThat(weeklyDigestService.collect(TODAY).topLinks()).containsExactly(
                new WeeklyDigest.TopLink("link1", "site1.example", 99),
                new WeeklyDigest.TopLink("link2", "site2.example", 98),
                new WeeklyDigest.TopLink("link3", "site3.example", 97),
                new WeeklyDigest.TopLink("link4", "site4.example", 96),
                new WeeklyDigest.TopLink("link5", "site5.example", 95));
        assertThat(Arrays.stream(WeeklyDigest.TopLink.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("shortUrl", "destinationHost", "clicks");
    }

    @Test
    void automaticBlocksLeaveOutEveryManualReasonOfTheSharedSet() {
        final var reasons = Arrays.stream(BlockReason.values()).map(Enum::name).toList();
        final var oneOfEach = new long[reasons.size()];
        Arrays.fill(oneOfEach, 1);
        stubEmptyWeekWith(links(WEEK_FROM, WEEK_TO, NO_SERIES, 0), safety(NO_SERIES, List.of(), series(reasons, oneOfEach)), List.of());

        assertThat(weeklyDigestService.collect(TODAY).automaticBlocks())
                .extracting(CountEntry::value)
                .containsExactlyElementsOf(Arrays.stream(BlockReason.values())
                        .filter(reason -> !BlockReason.MANUAL.contains(reason))
                        .map(Enum::name)
                        .toList())
                .doesNotContainAnyElementsOf(BlockReason.MANUAL.stream().map(Enum::name).toList());
    }

    @Test
    void postsOnceToDiscordAndEmailsTheAdmin(final CapturedOutput output) {
        readyToSend();
        discordAccepts();

        weeklyDigestService.sendWeeklyDigest();

        final var digest = emailedDigest();
        assertThat(digest.from()).isEqualTo(WEEK_FROM);
        assertThat(digest.to()).isEqualTo(WEEK_TO);
        assertThat(digest.topLinks()).containsExactly(new WeeklyDigest.TopLink("menu", "example.com", 120));
        verify(discordWebhookClient).send(WEBHOOK, formatter.weeklyDigest(digest), DISCORD_TIMEOUT);
        verifyNoMoreInteractions(discordWebhookClient, sendingEmailService);
        assertThat(output).contains("Weekly digest sent week=2026-10-19 discord=true email=true").doesNotContain("webhook-secret");
    }

    @Test
    void aSecondRunForTheSameWeekSendsNothing(final CapturedOutput output) {
        readyToSend();
        discordAccepts();

        weeklyDigestService.sendWeeklyDigest();
        weeklyDigestService.sendWeeklyDigest();

        verify(discordWebhookClient, times(1)).send(eq(WEBHOOK), any(DiscordMessage.class), eq(DISCORD_TIMEOUT));
        verify(sendingEmailService, times(1)).sendWeeklyDigestToAdmin(any(WeeklyDigest.class));
        assertThat(StringUtils.countMatches(output.getOut(), "Weekly digest sent week=")).isEqualTo(1);
        assertThat(StringUtils.countMatches(output.getOut(), "Weekly digest already sent week=2026-10-19")).isEqualTo(1);
    }

    @Test
    void aRestartOnMondayMorningDoesNotPostAgain() {
        readyToSend();
        discordAccepts();
        weeklyDigestService.sendWeeklyDigest();

        service().sendWeeklyDigest();

        verify(discordWebhookClient, times(1)).send(eq(WEBHOOK), any(DiscordMessage.class), eq(DISCORD_TIMEOUT));
        verify(sendingEmailService, times(1)).sendWeeklyDigestToAdmin(any(WeeklyDigest.class));
    }

    @Test
    void theOnceAWeekKeyIsTheMondayAndLastsEightDays() {
        readyToSend();
        discordAccepts();

        weeklyDigestService.sendWeeklyDigest();

        assertThat(redis.keys()).containsExactly(WEEK_KEY);
        assertThat(redis.timeToLiveOfKeyContaining("digest:weekly:")).isEqualTo(Duration.ofDays(8));
        assertThat(redisTemplate.opsForValue().get(WEEK_KEY)).isEqualTo("2026-10-26T07:00:00Z");
    }

    @Test
    void theNextMondaySendsTheNextWeek() {
        when(analyticsDays.today()).thenReturn(TODAY, TODAY.plusDays(7));
        stubAnyWeek();
        discordAccepts();

        weeklyDigestService.sendWeeklyDigest();
        weeklyDigestService.sendWeeklyDigest();

        assertThat(redis.keys()).containsExactlyInAnyOrder(WEEK_KEY, "digest:weekly:2026-10-26");
        final var digests = ArgumentCaptor.forClass(WeeklyDigest.class);
        verify(sendingEmailService, times(2)).sendWeeklyDigestToAdmin(digests.capture());
        assertThat(digests.getAllValues()).extracting(WeeklyDigest::from).containsExactly(WEEK_FROM, LocalDate.of(2026, 10, 26));
        verify(discordWebhookClient, times(2)).send(eq(WEBHOOK), any(DiscordMessage.class), eq(DISCORD_TIMEOUT));
    }

    @Test
    void redisDownSkipsTheDigestWithOneWarning(final CapturedOutput output) {
        readyToSend();
        final var valueOperations = redisTemplate.opsForValue();
        doThrow(new RedisConnectionFailureException("redis down at redis.hrvalabs.net"))
                .when(valueOperations).setIfAbsent(anyString(), anyString(), any(Duration.class));

        weeklyDigestService.sendWeeklyDigest();

        verify(adminStatsService).traffic(WEEK_FROM, WEEK_TO);
        verify(adminStatsService).safety(WEEK_FROM, WEEK_TO);
        verifyNoInteractions(discordWebhookClient, sendingEmailService);
        assertThat(StringUtils.countMatches(output.getOut(), "Weekly digest skipped")).isEqualTo(1);
        assertThat(output).contains("WARN")
                .contains("Weekly digest skipped: Redis is unavailable, so it can't make sure it is sent once "
                        + "exception=org.springframework.data.redis.RedisConnectionFailureException")
                .doesNotContain("Weekly digest sent")
                .doesNotContain("redis.hrvalabs.net");
    }

    @Test
    void aDatabaseFailureWhileCollectingLeavesTheWeekForTheNextRun() {
        readyToSend();
        when(adminStatsService.traffic(WEEK_FROM, WEEK_TO))
                .thenThrow(new DataAccessResourceFailureException("database down"))
                .thenReturn(traffic(WEEK_FROM, WEEK_TO, 0, 0, 0, List.of()));

        assertThatThrownBy(() -> weeklyDigestService.sendWeeklyDigest()).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(redis.isEmpty()).isTrue();
        verifyNoInteractions(discordWebhookClient, sendingEmailService);

        discordAccepts();
        weeklyDigestService.sendWeeklyDigest();

        assertThat(redis.keys()).containsExactly(WEEK_KEY);
        verify(sendingEmailService).sendWeeklyDigestToAdmin(any(WeeklyDigest.class));
        verify(discordWebhookClient).send(eq(WEBHOOK), any(DiscordMessage.class), eq(DISCORD_TIMEOUT));
    }

    @Test
    void discordOffKeepsTheEmail(final CapturedOutput output) {
        appProperties.getDigest().getWeekly().setEnabled(false);
        readyToSend();

        weeklyDigestService.sendWeeklyDigest();

        verify(sendingEmailService).sendWeeklyDigestToAdmin(any(WeeklyDigest.class));
        verifyNoInteractions(discordWebhookClient);
        assertThat(redis.keys()).containsExactly(WEEK_KEY);
        assertThat(output).contains("Weekly digest sent week=2026-10-19 discord=false email=true");
    }

    @Test
    void emailOffKeepsDiscord(final CapturedOutput output) {
        appProperties.getDigest().getWeekly().setEmail(false);
        readyToSend();
        discordAccepts();

        weeklyDigestService.sendWeeklyDigest();

        verify(discordWebhookClient).send(eq(WEBHOOK), any(DiscordMessage.class), eq(DISCORD_TIMEOUT));
        verifyNoInteractions(sendingEmailService);
        assertThat(redis.keys()).containsExactly(WEEK_KEY);
        assertThat(output).contains("Weekly digest sent week=2026-10-19 discord=true email=false");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aBlankWebhookSkipsDiscord(final String webhook) {
        appProperties.setDiscordWebhookUrl(webhook);
        readyToSend();

        weeklyDigestService.sendWeeklyDigest();

        verify(sendingEmailService).sendWeeklyDigestToAdmin(any(WeeklyDigest.class));
        verifyNoInteractions(discordWebhookClient);
    }

    @Test
    void bothOffReadsNothing(final CapturedOutput output) {
        appProperties.getDigest().getWeekly().setEnabled(false);
        appProperties.getDigest().getWeekly().setEmail(false);

        weeklyDigestService.sendWeeklyDigest();

        verifyNoInteractions(adminStatsService, analyticsDays, redisTemplate, discordWebhookClient, sendingEmailService);
        assertThat(output).contains("Weekly digest is off");
    }

    @Test
    void aBlankWebhookWithTheEmailOffReadsNothing(final CapturedOutput output) {
        appProperties.setDiscordWebhookUrl(" ");
        appProperties.getDigest().getWeekly().setEmail(false);

        weeklyDigestService.sendWeeklyDigest();

        verifyNoInteractions(adminStatsService, analyticsDays, redisTemplate, discordWebhookClient, sendingEmailService);
        assertThat(output).contains("Weekly digest is off");
    }

    @Test
    void aDiscordFailureStillSendsTheEmail(final CapturedOutput output) {
        readyToSend();
        when(discordWebhookClient.send(eq(WEBHOOK), any(DiscordMessage.class), eq(DISCORD_TIMEOUT))).thenReturn(false);

        weeklyDigestService.sendWeeklyDigest();

        verify(sendingEmailService).sendWeeklyDigestToAdmin(any(WeeklyDigest.class));
        assertThat(output).contains("Weekly digest sent week=2026-10-19 discord=false email=true");
    }

    @Test
    void anUnexpectedDiscordErrorStillSendsTheEmail(final CapturedOutput output) {
        readyToSend();
        when(discordWebhookClient.send(eq(WEBHOOK), any(DiscordMessage.class), eq(DISCORD_TIMEOUT)))
                .thenThrow(new IllegalStateException("broken " + WEBHOOK));

        weeklyDigestService.sendWeeklyDigest();

        verify(sendingEmailService).sendWeeklyDigestToAdmin(any(WeeklyDigest.class));
        assertThat(output).contains("Weekly digest couldn't be posted to Discord exception=java.lang.IllegalStateException")
                .contains("Weekly digest sent week=2026-10-19 discord=false email=true")
                .doesNotContain("webhook-secret");
    }

    @Test
    void anEmailThatCannotBeQueuedLeavesTheDiscordPost(final CapturedOutput output) {
        readyToSend();
        discordAccepts();
        doThrow(new TaskRejectedException("queue full")).when(sendingEmailService).sendWeeklyDigestToAdmin(any(WeeklyDigest.class));

        weeklyDigestService.sendWeeklyDigest();

        verify(discordWebhookClient).send(eq(WEBHOOK), any(DiscordMessage.class), eq(DISCORD_TIMEOUT));
        assertThat(output).contains("Weekly digest email couldn't be queued exception=org.springframework.core.task.TaskRejectedException")
                .contains("Weekly digest sent week=2026-10-19 discord=true email=false");
    }

    @Test
    void sendingRunsOutsideAnyTransaction() throws NoSuchMethodException {
        final var transactional = DefaultWeeklyDigestService.class.getMethod("sendWeeklyDigest").getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void noTransactionIsOpenWhileTheWeekIsClaimedOrTheDigestIsPostedAndEmailed() {
        final var transactions = new ArrayList<String>();
        readyToSend();
        final var valueOperations = redisTemplate.opsForValue();
        doAnswer(invocation -> {
            transactions.add("claim " + TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        }).when(valueOperations).setIfAbsent(anyString(), anyString(), any(Duration.class));
        when(discordWebhookClient.send(eq(WEBHOOK), any(DiscordMessage.class), eq(DISCORD_TIMEOUT))).thenAnswer(invocation -> {
            transactions.add("discord " + TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        doAnswer(invocation -> transactions.add("email " + TransactionSynchronizationManager.isActualTransactionActive()))
                .when(sendingEmailService).sendWeeklyDigestToAdmin(any(WeeklyDigest.class));

        transactional(service()).sendWeeklyDigest();

        assertThat(transactions).containsExactly("claim false", "discord false", "email false");
    }

    @Test
    void collectingOnItsOwnReadsInOneReadOnlyTransaction() {
        final var transactions = new ArrayList<String>();
        stubEmptyWeekWith(links(WEEK_FROM, WEEK_TO, NO_SERIES, 0), safety(NO_SERIES, List.of(), NO_SERIES), List.of());
        when(adminStatsService.traffic(WEEK_FROM, WEEK_TO)).thenAnswer(invocation -> {
            transactions.add("stats " + TransactionSynchronizationManager.isActualTransactionActive()
                    + " read-only " + TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return traffic(WEEK_FROM, WEEK_TO, 0, 0, 0, List.of());
        });

        transactional(service()).collect(TODAY);

        assertThat(transactions).containsExactly("stats true read-only true");
    }

    private DefaultWeeklyDigestService service() {
        return new DefaultWeeklyDigestService(adminStatsService, analyticsDays, appProperties, formatter, discordWebhookClient,
                sendingEmailService, redisTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WeeklyDigestService transactional(final WeeklyDigestService target) {
        final var proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor((TransactionManager) new NoResourceTransactionManager(),
                new AnnotationTransactionAttributeSource()));
        return (WeeklyDigestService) proxyFactory.getProxy();
    }

    private void readyToSend() {
        when(analyticsDays.today()).thenReturn(TODAY);
        stubEmptyWeekWith(links(WEEK_FROM, WEEK_TO, NO_SERIES, 0), safety(NO_SERIES, List.of(), NO_SERIES),
                List.of(topLink(1L, "menu", "example.com", 120)));
    }

    private void discordAccepts() {
        when(discordWebhookClient.send(eq(WEBHOOK), any(DiscordMessage.class), eq(DISCORD_TIMEOUT))).thenReturn(true);
    }

    private void stubAnyWeek() {
        when(adminStatsService.traffic(any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> traffic(invocation.getArgument(0), invocation.getArgument(1), 0, 0, 0, List.of()));
        when(adminStatsService.links(any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> links(invocation.getArgument(0), invocation.getArgument(1), NO_SERIES, 0));
        when(adminStatsService.users(any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(invocation -> users(invocation.getArgument(0), invocation.getArgument(1), NO_SERIES, 0));
        when(adminStatsService.safety(any(LocalDate.class), any(LocalDate.class))).thenReturn(safety(NO_SERIES, List.of(), NO_SERIES));
    }

    private WeeklyDigest emailedDigest() {
        final var digest = ArgumentCaptor.forClass(WeeklyDigest.class);
        verify(sendingEmailService).sendWeeklyDigestToAdmin(digest.capture());
        return digest.getValue();
    }

    private void stubWeek(final TrafficStatsResponse traffic, final LinksStatsResponse links, final UsersStatsResponse users,
                          final SafetyStatsResponse safety) {
        when(adminStatsService.traffic(WEEK_FROM, WEEK_TO)).thenReturn(traffic);
        when(adminStatsService.links(WEEK_FROM, WEEK_TO)).thenReturn(links);
        when(adminStatsService.users(WEEK_FROM, WEEK_TO)).thenReturn(users);
        when(adminStatsService.safety(WEEK_FROM, WEEK_TO)).thenReturn(safety);
    }

    private void stubWeekBefore(final TrafficStatsResponse traffic, final LinksStatsResponse links, final UsersStatsResponse users) {
        when(adminStatsService.traffic(BEFORE_FROM, BEFORE_TO)).thenReturn(traffic);
        when(adminStatsService.links(BEFORE_FROM, BEFORE_TO)).thenReturn(links);
        when(adminStatsService.users(BEFORE_FROM, BEFORE_TO)).thenReturn(users);
    }

    private void stubEmptyWeekWith(final LinksStatsResponse links, final SafetyStatsResponse safety,
                                   final List<TrafficStatsResponse.TopLink> topLinks) {
        stubWeek(traffic(WEEK_FROM, WEEK_TO, 0, 0, 0, topLinks), links, users(WEEK_FROM, WEEK_TO, NO_SERIES, 0), safety);
        stubWeekBefore(traffic(BEFORE_FROM, BEFORE_TO, 0, 0, 0, List.of()), links(BEFORE_FROM, BEFORE_TO, NO_SERIES, 0),
                users(BEFORE_FROM, BEFORE_TO, NO_SERIES, 0));
    }

    private static TrafficStatsResponse.TopLink topLink(final long id, final String shortUrl, final String host, final long clicks) {
        return new TrafficStatsResponse.TopLink(id, shortUrl, host, "owner" + id + "@example.com", clicks, clicks / 2);
    }

    private static TrafficStatsResponse traffic(final LocalDate from, final LocalDate to, final long clicks, final long uniqueVisitors,
                                                final long botClicks, final List<TrafficStatsResponse.TopLink> topLinks) {
        return new TrafficStatsResponse(7, from, to, new AnalyticsTotals(clicks, uniqueVisitors, botClicks), List.of(), 0, clicks,
                topLinks, new Breakdowns(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), 5000);
    }

    private static LinksStatsResponse links(final LocalDate from, final LocalDate to, final StackedSeries created, final long createdTotal) {
        return new LinksStatsResponse(7, from, to, created, new CreatedInRange(createdTotal, createdTotal, 0, 0),
                new LinkInventory(500, 480, 20, 3, 4, 5, 6, List.of(new CountEntry("ADMIN", 9))), List.of(), List.of(), List.of());
    }

    private static UsersStatsResponse users(final LocalDate from, final LocalDate to, final StackedSeries signups, final long activeLast7Days) {
        return new UsersStatsResponse(7, from, to, signups, new UserInventory(80, 78, 2, 1, 70, 10, List.of()), activeLast7Days, 40,
                new ApiKeyCounts(0, 0, 0, 0), new McpCounts(0, 0));
    }

    private static SafetyStatsResponse safety(final StackedSeries reviewsOpened, final List<CountEntry> resolved,
                                              final StackedSeries blocked) {
        return new SafetyStatsResponse(7, WEEK_FROM, WEEK_TO, reviewsOpened, 6, 2, resolved, new ResolveTimes(0, null, null), blocked,
                new DomainRuleCounts(0, 0), 0, List.of());
    }

    private static StackedSeries series(final List<String> keys, final long[]... days) {
        final var daily = IntStream.range(0, days.length)
                .mapToObj(day -> new DailyCounts(WEEK_FROM.plusDays(day), countsOf(keys, days[day])))
                .toList();
        return new StackedSeries(keys, daily);
    }

    private static Map<String, Long> countsOf(final List<String> keys, final long[] counts) {
        final var byKey = new LinkedHashMap<String, Long>();
        IntStream.range(0, keys.size()).forEach(index -> byKey.put(keys.get(index), counts[index]));
        return byKey;
    }

    private static final class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(final Object transaction, final TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(final DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(final DefaultTransactionStatus status) {
        }

    }

}
