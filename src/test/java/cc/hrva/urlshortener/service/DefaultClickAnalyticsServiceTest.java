package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.model.enums.StatDimension;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.analytics.ReferrerClassifier;
import cc.hrva.urlshortener.service.analytics.UniqueVisitorCounter;
import cc.hrva.urlshortener.service.impl.DefaultClickAnalyticsService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cc.hrva.urlshortener.model.enums.StatDimension.BROWSER;
import static cc.hrva.urlshortener.model.enums.StatDimension.COUNTRY;
import static cc.hrva.urlshortener.model.enums.StatDimension.DEVICE;
import static cc.hrva.urlshortener.model.enums.StatDimension.OS;
import static cc.hrva.urlshortener.model.enums.StatDimension.REFERRER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultClickAnalyticsServiceTest {

    private static final long URL_ID = 7L;
    private static final String SHORT_URL = "abc";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String SAFARI_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
    private static final ClickRequest CLICK = ClickRequest.of(CLIENT_IP, SAFARI_IPHONE, "https://t.co/x", "hr");
    private static final Map<StatDimension, String> CLICK_BREAKDOWN = Map.of(
            REFERRER, "t.co", COUNTRY, "HR", DEVICE, "mobile", BROWSER, "safari", OS, "ios");
    private static final String WRITE_FAILED = "Click analytics write failed urlId=7 step=";

    @Mock
    private LinkStatsRepository linkStatsRepository;

    @Mock
    private UniqueVisitorCounter uniqueVisitorCounter;

    @Mock
    private AnalyticsDays analyticsDays;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @Captor
    private ArgumentCaptor<Map<StatDimension, String>> breakdown;

    private final TaskExecutor executor = Runnable::run;

    private ReferrerClassifier referrerClassifier;

    private ClickAnalyticsService clickAnalyticsService;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("https://app.hrva.cc");
        referrerClassifier = new ReferrerClassifier(appProperties);
        referrerClassifier.init();
        lenient().when(analyticsDays.today()).thenReturn(DAY);
        lenient().when(cacheManager.getCache("urls")).thenReturn(cache);
        clickAnalyticsService = service(executor);
    }

    @Test
    void botClickCountsOnlyABotClick() {
        clickAnalyticsService.recordClick(URL_ID, SHORT_URL, ClickRequest.of(CLIENT_IP, "Discordbot/2.0", "https://t.co/x", "hr"));

        verify(linkStatsRepository).incrementDailyStats(URL_ID, DAY, 0, 0, 1);
        verifyNoInteractions(uniqueVisitorCounter);
        verify(urlRepository, never()).recordVisit(anyLong(), any());
        verify(linkStatsRepository, never()).findBreakdownValues(anyLong(), any());
        verify(linkStatsRepository, never()).incrementBreakdown(anyLong(), any(), anyMap());
    }

    @Test
    void newVisitorCountsAVisitAndAllFiveBreakdowns() {
        when(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).thenReturn(true);

        clickAnalyticsService.recordClick(URL_ID, SHORT_URL, CLICK);

        verify(urlRepository).recordVisit(eq(URL_ID), any(LocalDateTime.class));
        verify(urlRepository).deactivateAtVisitLimit(URL_ID);
        verify(linkStatsRepository).incrementDailyStats(URL_ID, DAY, 1, 1, 0);
        verify(linkStatsRepository).incrementBreakdown(URL_ID, DAY, CLICK_BREAKDOWN);
        verify(cache, never()).evict(any());
    }

    @Test
    void repeatVisitorCountsAClickButNoVisit() {
        when(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).thenReturn(false);

        clickAnalyticsService.recordClick(URL_ID, SHORT_URL, CLICK);

        verify(linkStatsRepository).incrementDailyStats(URL_ID, DAY, 1, 0, 0);
        verify(linkStatsRepository).incrementBreakdown(URL_ID, DAY, CLICK_BREAKDOWN);
        verify(urlRepository, never()).recordVisit(anyLong(), any());
        verify(urlRepository, never()).deactivateAtVisitLimit(anyLong());
    }

    @Test
    void reachingTheLimitEvictsTheCachedLink() {
        when(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).thenReturn(true);
        when(urlRepository.deactivateAtVisitLimit(URL_ID)).thenReturn(1);

        clickAnalyticsService.recordClick(URL_ID, SHORT_URL, CLICK);

        verify(cache).evict(SHORT_URL);
        verify(linkStatsRepository).incrementDailyStats(URL_ID, DAY, 1, 1, 0);
    }

    @Test
    void aFailingCacheEvictionStillCountsTheClick() {
        when(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).thenReturn(true);
        when(urlRepository.deactivateAtVisitLimit(URL_ID)).thenReturn(1);
        doThrow(new RedisConnectionFailureException("Unable to connect to Redis")).when(cache).evict(SHORT_URL);

        assertThatCode(() -> clickAnalyticsService.recordClick(URL_ID, SHORT_URL, CLICK)).doesNotThrowAnyException();

        verify(linkStatsRepository).incrementDailyStats(URL_ID, DAY, 1, 1, 0);
        verify(linkStatsRepository).incrementBreakdown(URL_ID, DAY, CLICK_BREAKDOWN);
    }

    @Test
    void the51stNewValueOfTheDayIsCountedAsOther() {
        when(linkStatsRepository.findBreakdownValues(URL_ID, DAY)).thenReturn(Map.of(REFERRER, sites(50)));

        clickAnalyticsService.recordClick(URL_ID, SHORT_URL, CLICK);
        clickAnalyticsService.recordClick(URL_ID, SHORT_URL, ClickRequest.of(CLIENT_IP, SAFARI_IPHONE, "https://site3.com/menu", "hr"));

        verify(linkStatsRepository, times(2)).incrementBreakdown(eq(URL_ID), eq(DAY), breakdown.capture());
        assertThat(breakdown.getAllValues()).extracting(values -> values.get(REFERRER)).containsExactly("other", "site3.com");
        assertThat(breakdown.getAllValues()).extracting(values -> values.get(COUNTRY)).containsExactly("HR", "HR");
    }

    @Test
    void otherIsNotANamedValue() {
        final var stored = sites(49);
        stored.add("other");
        when(linkStatsRepository.findBreakdownValues(URL_ID, DAY)).thenReturn(Map.of(REFERRER, stored));

        clickAnalyticsService.recordClick(URL_ID, SHORT_URL, CLICK);

        verify(linkStatsRepository).incrementBreakdown(URL_ID, DAY, CLICK_BREAKDOWN);
    }

    @Test
    void aValueTooLongForItsColumnIsCountedAsOther() {
        final var classifier = mock(ReferrerClassifier.class);
        final var service = new DefaultClickAnalyticsService(executor, linkStatsRepository, uniqueVisitorCounter, classifier,
                analyticsDays, urlRepository, TransactionOperations.withoutTransaction(), cacheManager);
        final var longest = "app:" + "a".repeat(251);
        when(classifier.classify(any(ClickRequest.class))).thenReturn(longest, longest + "b");

        service.recordClick(URL_ID, SHORT_URL, CLICK);
        service.recordClick(URL_ID, SHORT_URL, CLICK);

        verify(linkStatsRepository, times(2)).incrementBreakdown(eq(URL_ID), eq(DAY), breakdown.capture());
        assertThat(breakdown.getAllValues()).extracting(values -> values.get(REFERRER)).containsExactly(longest, "other");
    }

    @Test
    void theStatsIncrementsRunWithoutATransactionAndTheVisitInsideOne() {
        final var transactions = new TransactionTemplate(new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:")));
        final var service = new DefaultClickAnalyticsService(executor, linkStatsRepository, uniqueVisitorCounter, referrerClassifier,
                analyticsDays, urlRepository, transactions, cacheManager);
        final var visitInTransaction = new AtomicReference<Boolean>();
        final var statsInTransaction = new AtomicReference<Boolean>();
        final var breakdownReadInTransaction = new AtomicReference<Boolean>();
        final var breakdownInTransaction = new AtomicReference<Boolean>();
        when(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).thenReturn(true);
        when(urlRepository.recordVisit(eq(URL_ID), any(LocalDateTime.class))).thenAnswer(invocation -> {
            visitInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        doAnswer(invocation -> {
            statsInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(linkStatsRepository).incrementDailyStats(URL_ID, DAY, 1, 1, 0);
        when(linkStatsRepository.findBreakdownValues(URL_ID, DAY)).thenAnswer(invocation -> {
            breakdownReadInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Map.of();
        });
        doAnswer(invocation -> {
            breakdownInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(linkStatsRepository).incrementBreakdown(URL_ID, DAY, CLICK_BREAKDOWN);

        service.recordClick(URL_ID, SHORT_URL, CLICK);

        assertThat(visitInTransaction).hasValue(true);
        assertThat(statsInTransaction).hasValue(false);
        assertThat(breakdownReadInTransaction).hasValue(false);
        assertThat(breakdownInTransaction).hasValue(false);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailingStatsWriteStillCountsTheVisitAndTheBreakdown(final CapturedOutput output) {
        when(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("connection refused")).when(linkStatsRepository).incrementDailyStats(URL_ID, DAY, 1, 1, 0);

        assertThatCode(() -> clickAnalyticsService.recordClick(URL_ID, SHORT_URL, CLICK)).doesNotThrowAnyException();

        verify(urlRepository).recordVisit(eq(URL_ID), any(LocalDateTime.class));
        verify(linkStatsRepository).incrementBreakdown(URL_ID, DAY, CLICK_BREAKDOWN);
        assertThat(output).contains(WRITE_FAILED + "stats exception=" + DataAccessResourceFailureException.class.getName());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailingVisitStillCountsTheClickAndTheBreakdown(final CapturedOutput output) {
        when(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).thenReturn(true);
        when(urlRepository.recordVisit(eq(URL_ID), any(LocalDateTime.class))).thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatCode(() -> clickAnalyticsService.recordClick(URL_ID, SHORT_URL, CLICK)).doesNotThrowAnyException();

        verify(linkStatsRepository).incrementDailyStats(URL_ID, DAY, 1, 1, 0);
        verify(linkStatsRepository).incrementBreakdown(URL_ID, DAY, CLICK_BREAKDOWN);
        assertThat(output).contains(WRITE_FAILED + "visit exception=" + DataAccessResourceFailureException.class.getName());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aRejectedTaskNeverReachesTheCaller(final CapturedOutput output) {
        final var rejecting = mock(TaskExecutor.class);
        doThrow(new TaskRejectedException("queue full")).when(rejecting).execute(any(Runnable.class));

        assertThatCode(() -> service(rejecting).recordClick(URL_ID, SHORT_URL, CLICK)).doesNotThrowAnyException();

        verifyNoInteractions(linkStatsRepository, urlRepository);
        assertThat(output).contains("Click analytics dropped urlId=7 exception=" + TaskRejectedException.class.getName());
    }

    @Test
    void unlockClicksRecordUnknownReferrer() {
        final var click = CLICK.withUnknownReferrer();

        clickAnalyticsService.recordClick(URL_ID, SHORT_URL, click);

        verify(linkStatsRepository).incrementBreakdown(eq(URL_ID), eq(DAY), breakdown.capture());
        assertThat(breakdown.getValue()).containsEntry(REFERRER, "unknown");
    }

    @Test
    void dayIsTakenWhenTheClickArrives() {
        final var tasks = new ArrayList<Runnable>();
        when(analyticsDays.today()).thenReturn(DAY, DAY.plusDays(1));

        service(tasks::add).recordClick(URL_ID, SHORT_URL, CLICK);

        verifyNoInteractions(linkStatsRepository);
        assertThat(tasks).hasSize(1);
        tasks.getFirst().run();
        verify(uniqueVisitorCounter).isNewVisitor(URL_ID, DAY, CLICK);
        verify(linkStatsRepository).incrementDailyStats(URL_ID, DAY, 1, 0, 0);
        verify(linkStatsRepository).findBreakdownValues(URL_ID, DAY);
        verify(linkStatsRepository).incrementBreakdown(URL_ID, DAY, CLICK_BREAKDOWN);
    }

    @Test
    void nullLinkIdRecordsNothing() {
        final var recording = mock(TaskExecutor.class);

        service(recording).recordClick(null, SHORT_URL, CLICK);

        verifyNoInteractions(recording, analyticsDays, linkStatsRepository, urlRepository, uniqueVisitorCounter);
    }

    @Test
    void isBotTreatsAFailureAsHuman() {
        assertThat(clickAnalyticsService.isBot(null)).isFalse();
        assertThat(clickAnalyticsService.isBot(ClickRequest.of("1.2.3.4", "curl/8.7.1", null, null))).isTrue();
        assertThat(clickAnalyticsService.isBot(CLICK)).isFalse();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsNeitherIpNorUserAgent(final CapturedOutput output) {
        final var failure = new DataAccessResourceFailureException("refused for " + CLIENT_IP + " " + SAFARI_IPHONE);
        when(uniqueVisitorCounter.isNewVisitor(URL_ID, DAY, CLICK)).thenReturn(true);
        when(urlRepository.recordVisit(anyLong(), any())).thenThrow(failure);
        doThrow(failure).when(linkStatsRepository).incrementDailyStats(anyLong(), any(), anyLong(), anyLong(), anyLong());
        when(linkStatsRepository.findBreakdownValues(anyLong(), any())).thenThrow(failure);

        assertThatCode(() -> {
            clickAnalyticsService.recordClick(URL_ID, SHORT_URL, CLICK);
            clickAnalyticsService.recordClick(URL_ID, SHORT_URL, ClickRequest.of(CLIENT_IP, "Discordbot/2.0", null, null));
        }).doesNotThrowAnyException();

        assertThat(output)
                .contains(WRITE_FAILED + "visit")
                .contains(WRITE_FAILED + "stats")
                .contains(WRITE_FAILED + "breakdown")
                .contains(WRITE_FAILED + "bot")
                .doesNotContain(CLIENT_IP)
                .doesNotContain(SAFARI_IPHONE)
                .doesNotContain("Discordbot")
                .doesNotContain("https://t.co/x");
    }

    private ClickAnalyticsService service(final TaskExecutor taskExecutor) {
        return new DefaultClickAnalyticsService(taskExecutor, linkStatsRepository, uniqueVisitorCounter, referrerClassifier,
                analyticsDays, urlRepository, TransactionOperations.withoutTransaction(), cacheManager);
    }

    private static Set<String> sites(final int count) {
        return new HashSet<>(IntStream.range(0, count).mapToObj(index -> "site" + index + ".com").toList());
    }

}
