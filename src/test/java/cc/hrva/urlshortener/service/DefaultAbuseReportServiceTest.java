package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.FetchResult;
import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.dto.abuse.AbuseReportLink;
import cc.hrva.urlshortener.dto.abuse.AbuseReportRequest;
import cc.hrva.urlshortener.dto.abuse.AbuseReportResponse;
import cc.hrva.urlshortener.dto.abuse.ReportedDomainsDto;
import cc.hrva.urlshortener.dto.campaign.ReportStatus;
import cc.hrva.urlshortener.exception.CampaignNotFoundException;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.ReportedDomain;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.repository.ReportedDomainRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.abuse.AbuseReportComposer;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainsSuspended;
import cc.hrva.urlshortener.service.alert.DiscordAlertFormatter;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.service.impl.DefaultAbuseReportService;
import cc.hrva.urlshortener.service.impl.DefaultDomainAgeService;
import cc.hrva.urlshortener.service.rdap.DomainRecord;
import cc.hrva.urlshortener.service.rdap.RdapAnswers;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAbuseReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T05:20:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 10, 30, 5, 20);
    private static final String ADMIN = "karlo@hrva.cc";
    private static final LocalDate REGISTERED = LocalDate.of(2026, 8, 14);
    private static final String NAMESILO = "NameSilo, LLC";
    private static final String PENDING = "abuse:pending-suspension:";
    private static final String PHISHING_PAGE = "https://wwkn0gb.bgp37.sbs/?r=plfN8D&t=1793210826";

    @Mock
    private CampaignService campaignService;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private LinkStatsRepository linkStatsRepository;

    @Mock
    private ReportedDomainRepository reportedDomainRepository;

    @Mock
    private DomainAgeService domainAgeService;

    @Mock
    private SettingsService settingsService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> pendingMarks;

    private final Map<String, Duration> pending = new HashMap<>();
    private final AppProperties appProperties = new AppProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final Map<Long, AbuseReportLink> links = new LinkedHashMap<>();
    private DefaultAbuseReportService abuseReportService;

    @BeforeEach
    void setUp() {
        lenient().when(settingsService.text(SettingKey.ABUSE_REPORTER_NAME)).thenReturn("Karlo Hrvacic Test");
        lenient().when(settingsService.addresses(SettingKey.ABUSE_REPORTER_ADDRESS)).thenReturn(List.of("abuse@hrva.cc"));
        lenient().when(settingsService.addresses(SettingKey.ABUSE_CC)).thenReturn(List.of("reportphishing@apwg.org"));
        lenient().when(settingsService.number(SettingKey.ABUSE_RECHECK_DAYS)).thenReturn(30);
        lenient().when(urlRepository.findAbuseReportLinksByIdIn(anyCollection())).thenAnswer(invocation -> invocation.<Collection<Long>>getArgument(0)
                .stream().filter(links::containsKey).map(links::get).toList());
        lenient().when(linkStatsRepository.sumClicksByUrl(anyCollection(), any(), any())).thenReturn(Map.of());
        lenient().when(reportedDomainRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(reportedDomainRepository.markSuspended(anyString(), any())).thenReturn(1);
        lenient().when(redisTemplate.opsForValue()).thenReturn(pendingMarks);
        lenient().when(pendingMarks.get(anyString())).thenAnswer(invocation -> pending.containsKey(invocation.<String>getArgument(0)) ? "1" : null);
        lenient().doAnswer(invocation -> pending.put(invocation.getArgument(0), invocation.getArgument(2))).when(pendingMarks)
                .set(anyString(), anyString(), any(Duration.class));
        lenient().when(redisTemplate.delete(anyString())).thenAnswer(invocation -> pending.remove(invocation.<String>getArgument(0)) != null);
        abuseReportService = service(domainAgeService);
    }

    @Test
    void groupsByAbuseEmail() {
        link(1, "https://ll.omaehhm.com/wqamhcsom/?r=a", PHISHING_PAGE, LocalDateTime.of(2026, 10, 28, 22, 30));
        link(2, "https://qe.buokpky.com/wqamhcsom/?r=b", PHISHING_PAGE, LocalDateTime.of(2026, 10, 29, 8, 0));
        link(3, "https://shop.other.net/login", null, LocalDateTime.of(2026, 10, 29, 9, 0));
        link(4, "https://203.0.113.5/login", null, LocalDateTime.of(2026, 10, 29, 9, 0));
        link(5, "https://qe.buokpky.com/wqamhcsom/?r=c", PHISHING_PAGE, LocalDateTime.of(2026, 10, 29, 9, 0));
        records(found("omaehhm.com", NAMESILO, "abuse@namesilo.com"), found("buokpky.com", NAMESILO, "Abuse@NameSilo.com"),
                found("other.net", "Other Registrar", "abuse@other.example"));
        when(linkStatsRepository.sumClicksByUrl(anyCollection(), eq(LocalDate.of(2026, 10, 28)), eq(LocalDate.of(2026, 10, 30))))
                .thenReturn(Map.of(1L, 40L, 2L, 2L, 3L, 7L));
        when(linkStatsRepository.topCountries(List.of(1L, 2L, 5L), 3)).thenReturn(List.of(new BreakdownEntry("PT", 30)));
        final var reported = new ReportedDomain("omaehhm.com", NAMESILO, "abuse@namesilo.com", ADMIN, NOW_UTC.minusDays(2), null, null);
        when(reportedDomainRepository.findAllById(any())).thenReturn(List.of(reported));

        final var response = abuseReportService.prepare(new AbuseReportRequest(List.of(1L, 2L, 3L, 4L, 5L), null));

        assertThat(response.reports()).extracting(AbuseReportResponse.Report::registrar, AbuseReportResponse.Report::to, AbuseReportResponse.Report::cc)
                .containsExactly(
                        tuple(NAMESILO, "Abuse@NameSilo.com", List.of("reportphishing@apwg.org")),
                        tuple("Other Registrar", "abuse@other.example", List.of("reportphishing@apwg.org")));
        final var nameSilo = response.reports().getFirst();
        assertThat(nameSilo.domains()).containsExactly(
                new AbuseReportResponse.Domain("buokpky.com", REGISTERED, null),
                new AbuseReportResponse.Domain("omaehhm.com", REGISTERED, ReportStatus.from(reported)));
        assertThat(nameSilo.subject()).isEqualTo("Phishing: 2 domains used in links to wwkn0gb.bgp37.sbs");
        assertThat(nameSilo.body()).contains("Between 28 October 2026 and 29 October 2026, short links")
                .contains("These links had 42 clicks on hrva.cc, mostly from Portugal (30). 3 of the 3 links are still on");
        assertThat(response.reports().get(1).subject()).isEqualTo("Phishing: 1 domain used in links to a phishing page");
        assertThat(response.noContact()).isEmpty();
        verify(domainAgeService).records(List.of("buokpky.com", "omaehhm.com", "other.net"), false);
        verifyNoInteractions(auditLogService, eventPublisher);
    }

    @Test
    void createDatesAreReadAsZagrebDays() {
        link(1, "https://ll.omaehhm.com/a", null, LocalDateTime.of(2026, 10, 27, 23, 30));
        records(found("omaehhm.com", NAMESILO, "abuse@namesilo.com"));

        final var body = abuseReportService.prepare(new AbuseReportRequest(List.of(1L), null)).reports().getFirst().body();

        assertThat(body).contains("On 28 October 2026, short links");
    }

    @Test
    void noContactListsTheRegistrarWhenKnown() {
        link(1, "https://a.no-email.com/", null, NOW_UTC);
        link(2, "https://b.failed.com/", null, NOW_UTC);
        link(3, "https://c.gone.com/", null, NOW_UTC);
        link(4, "https://www.njuskalo.hr/", null, NOW_UTC);
        link(5, "https://x.held.com/", null, NOW_UTC);
        records(new DomainRecord("no-email.com", DomainRecord.Status.FOUND, REGISTERED, "Quiet Registrar", null, List.of()),
                DomainRecord.failed("failed.com"), DomainRecord.notFound("gone.com"), DomainRecord.noRdapServer("njuskalo.hr"),
                new DomainRecord("held.com", DomainRecord.Status.FOUND, REGISTERED, null, "abuse@registry.example", List.of("server hold")));

        final var response = abuseReportService.prepare(new AbuseReportRequest(List.of(1L, 2L, 3L, 4L, 5L), null));

        assertThat(response.noContact()).containsExactly(
                new AbuseReportResponse.NoContact("failed.com", null, true, AbuseReportResponse.Reason.LOOKUP_FAILED, null),
                new AbuseReportResponse.NoContact("gone.com", null, false, AbuseReportResponse.Reason.NOT_REGISTERED, null),
                new AbuseReportResponse.NoContact("njuskalo.hr", null, false, AbuseReportResponse.Reason.NO_REGISTRY, null),
                new AbuseReportResponse.NoContact("no-email.com", "Quiet Registrar", false, AbuseReportResponse.Reason.NO_ABUSE_EMAIL, null));
        assertThat(response.reports()).singleElement().satisfies(report -> {
            assertThat(report.registrar()).isEqualTo("registry.example");
            assertThat(report.to()).isEqualTo("abuse@registry.example");
        });
    }

    @Test
    void aHostileRegistryRecordLeavesTheDomainWithoutAContact() {
        link(1, "https://a.hostile.com/", null, NOW_UTC);
        link(2, "https://b.listed.com/", null, NOW_UTC);
        records(RdapAnswers.found("hostile.com", fixture("hostile.json")),
                new DomainRecord("listed.com", DomainRecord.Status.FOUND, REGISTERED, "Listed Registrar", "abuse@a.example, abuse@b.example", List.of()));

        final var response = abuseReportService.prepare(new AbuseReportRequest(List.of(1L, 2L), null));

        assertThat(response.reports()).isEmpty();
        assertThat(response.noContact())
                .extracting(AbuseReportResponse.NoContact::domain, AbuseReportResponse.NoContact::lookupFailed, AbuseReportResponse.NoContact::reason)
                .containsExactly(tuple("hostile.com", false, AbuseReportResponse.Reason.NO_ABUSE_EMAIL),
                        tuple("listed.com", false, AbuseReportResponse.Reason.NO_ABUSE_EMAIL));
        assertThat(response.noContact().getFirst().registrar()).startsWith("Evil Registrar XXX").hasSize(255);
        assertThat(response.noContact().get(1).registrar()).isEqualTo("Listed Registrar");
    }

    @Test
    void aCardsRegistrarIsNeverBlank() {
        link(1, "https://a.blank.com/", null, NOW_UTC);
        records(new DomainRecord("blank.com", DomainRecord.Status.FOUND, REGISTERED, "  ", "abuse@registry.example", List.of()));

        final var report = abuseReportService.prepare(new AbuseReportRequest(List.of(1L), null)).reports().getFirst();

        assertThat(report.registrar()).isEqualTo("registry.example");
        assertThat(report.body()).contains("registered with registry.example:\n");
    }

    @Test
    void aSharedSuffixHostHasNoContactAndIsNotLookedUp() {
        link(1, "https://evil.pages.dev/login", null, NOW_UTC);
        link(2, "https://ll.omaehhm.com/a", null, NOW_UTC);
        records(found("omaehhm.com", NAMESILO, "abuse@namesilo.com"));
        final var reported = new ReportedDomain("evil.pages.dev", null, null, ADMIN, NOW_UTC.minusDays(1), null, null);
        when(reportedDomainRepository.findAllById(any())).thenReturn(List.of(reported));

        final var response = abuseReportService.prepare(new AbuseReportRequest(List.of(1L, 2L), null));

        verify(domainAgeService).records(List.of("omaehhm.com"), false);
        assertThat(response.noContact()).containsExactly(new AbuseReportResponse.NoContact("evil.pages.dev", null, false,
                AbuseReportResponse.Reason.SHARED_SUFFIX, ReportStatus.from(reported)));
        assertThat(response.reports()).extracting(AbuseReportResponse.Report::registrar).containsExactly(NAMESILO);
    }

    @Test
    void noLinksWithADomainAnswerAnEmptyReport() {
        link(1, "https://203.0.113.5/", null, NOW_UTC);

        final var response = abuseReportService.prepare(new AbuseReportRequest(List.of(1L, 99L), null));

        assertThat(response).isEqualTo(new AbuseReportResponse(List.of(), List.of()));
        verifyNoInteractions(domainAgeService);
    }

    @Test
    void byCampaignCoversEveryLinkInChunks() {
        final var ids = LongStream.rangeClosed(1, 2_500).boxed().toList();
        ids.forEach(id -> link(id, "https://l" + id + ".omaehhm.com/wqamhcsom", PHISHING_PAGE, NOW_UTC));
        when(campaignService.linkIds(4L)).thenReturn(ids);
        records(found("omaehhm.com", NAMESILO, "abuse@namesilo.com"));
        final var chunks = ArgumentCaptor.<Collection<Long>>captor();

        final var response = abuseReportService.prepare(new AbuseReportRequest(null, 4L));

        verify(urlRepository, times(3)).findAbuseReportLinksByIdIn(chunks.capture());
        assertThat(chunks.getAllValues()).extracting(Collection::size).containsExactly(1_000, 1_000, 500);
        verify(linkStatsRepository, times(3)).sumClicksByUrl(anyCollection(), any(), any());
        verify(linkStatsRepository).topCountries(ids, 3);
        assertThat(response.reports()).singleElement().satisfies(report -> assertThat(report.subject())
                .isEqualTo("Phishing: 1 domain used in links to wwkn0gb.bgp37.sbs"));
    }

    @Test
    void anUnknownCampaignIsNotFound() {
        when(campaignService.linkIds(99L)).thenThrow(new CampaignNotFoundException("There is no campaign 99."));

        assertThatThrownBy(() -> abuseReportService.prepare(new AbuseReportRequest(null, 99L))).isInstanceOf(CampaignNotFoundException.class);
        verifyNoInteractions(domainAgeService);
    }

    @Test
    void bothOrNeitherIsRefused() {
        for (final var request : List.of(new AbuseReportRequest(List.of(1L), 4L), new AbuseReportRequest(null, null))) {
            assertThatThrownBy(() -> abuseReportService.prepare(request))
                    .isInstanceOfSatisfying(FieldValidationException.class, refusal -> assertThat(refusal.getFieldErrors())
                            .isEqualTo(Map.of("urlIds", "Select between 1 and 100 links")));
        }
        verifyNoInteractions(campaignService, urlRepository, domainAgeService);
    }

    @Test
    void markReportedUpsertsAndAudits() {
        when(reportedDomainRepository.upsert("omaehhm.com", NAMESILO, "abuse@namesilo.com", ADMIN, NOW_UTC))
                .thenThrow(new DataIntegrityViolationException("duplicate key")).thenReturn(1);

        abuseReportService.markReported(new ReportedDomainsDto(List.of(" OmaeHHM.com ", "buokpky.com"), NAMESILO, "abuse@namesilo.com"), ADMIN);

        verify(reportedDomainRepository, times(2)).upsert("omaehhm.com", NAMESILO, "abuse@namesilo.com", ADMIN, NOW_UTC);
        verify(reportedDomainRepository).upsert("buokpky.com", NAMESILO, "abuse@namesilo.com", ADMIN, NOW_UTC);
        verify(auditLogService).log(ADMIN, AuditAction.REPORT_DOMAINS, "Registrar", "abuse@namesilo.com", "domains: 2, registrar: NameSilo, LLC");
    }

    @Test
    void markingWithoutAContactAuditsAnUnknownRegistrar() {
        abuseReportService.markReported(new ReportedDomainsDto(List.of("example.hr"), null, null), ADMIN);

        verify(reportedDomainRepository).upsert("example.hr", null, null, ADMIN, NOW_UTC);
        verify(auditLogService).log(ADMIN, AuditAction.REPORT_DOMAINS, "Registrar", null, "domains: 1, registrar: unknown");
    }

    @Test
    void recheckSuspendsOnHoldOr404Only() {
        pendingSince("a.com", "b.com", "c.com", "d.hr", "e.com");
        when(reportedDomainRepository.findToRecheck(NOW_UTC.minusDays(30))).thenReturn(List.of("a.com", "b.com", "c.com", "d.hr", "e.com"));
        records(new DomainRecord("a.com", DomainRecord.Status.FOUND, REGISTERED, NAMESILO, null, List.of("client hold")),
                DomainRecord.notFound("b.com"),
                new DomainRecord("c.com", DomainRecord.Status.FOUND, REGISTERED, NAMESILO, null, List.of("pending delete", "client transfer prohibited")),
                DomainRecord.noRdapServer("d.hr"), DomainRecord.failed("e.com"));

        abuseReportService.recheck();

        verify(domainAgeService).records(List.of("a.com", "b.com", "c.com", "d.hr", "e.com"), true);
        verify(reportedDomainRepository).markSuspended("a.com", NOW_UTC);
        verify(reportedDomainRepository).markSuspended("b.com", NOW_UTC);
        verify(reportedDomainRepository, times(2)).markSuspended(anyString(), any());
        verify(reportedDomainRepository).markChecked(List.of("c.com", "d.hr"), NOW_UTC);
        verify(eventPublisher).publishEvent(new DomainsSuspended(List.of("a.com", "b.com")));
        assertThat(pending).containsOnlyKeys(PENDING + "e.com");
    }

    @Test
    void aFirstSuspendedAnswerOnlyMarksTheDomainPendingForADayAndAHalf() {
        when(reportedDomainRepository.findToRecheck(any())).thenReturn(List.of("a.com", "b.com"));
        records(new DomainRecord("a.com", DomainRecord.Status.FOUND, REGISTERED, NAMESILO, null, List.of("serverHold")), DomainRecord.notFound("b.com"));

        abuseReportService.recheck();

        verify(reportedDomainRepository, never()).markSuspended(anyString(), any());
        verify(reportedDomainRepository).markChecked(List.of("a.com", "b.com"), NOW_UTC);
        verifyNoInteractions(eventPublisher);
        assertThat(pending).containsOnly(Map.entry(PENDING + "a.com", Duration.ofHours(36)), Map.entry(PENDING + "b.com", Duration.ofHours(36)));

        abuseReportService.recheck();

        verify(reportedDomainRepository).markSuspended("a.com", NOW_UTC);
        verify(reportedDomainRepository).markSuspended("b.com", NOW_UTC);
        verify(eventPublisher).publishEvent(new DomainsSuspended(List.of("a.com", "b.com")));
        assertThat(pending).isEmpty();
    }

    @Test
    void aHealthyAnswerClearsThePendingMark() {
        pendingSince("a.com");
        when(reportedDomainRepository.findToRecheck(any())).thenReturn(List.of("a.com"));
        records(found("a.com", NAMESILO, "abuse@namesilo.com"));

        abuseReportService.recheck();

        assertThat(pending).isEmpty();
        verify(reportedDomainRepository).markChecked(List.of("a.com"), NOW_UTC);
        verify(reportedDomainRepository, never()).markSuspended(anyString(), any());
    }

    @Test
    void withRedisDownNothingIsMarkedSuspended() {
        when(reportedDomainRepository.findToRecheck(any())).thenReturn(List.of("a.com"));
        records(DomainRecord.notFound("a.com"));
        when(pendingMarks.get(anyString())).thenThrow(new RedisConnectionFailureException("Redis is down"));
        doThrow(new RedisConnectionFailureException("Redis is down")).when(pendingMarks).set(anyString(), anyString(), any(Duration.class));

        abuseReportService.recheck();

        verify(reportedDomainRepository, never()).markSuspended(anyString(), any());
        verify(reportedDomainRepository).markChecked(List.of("a.com"), NOW_UTC);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aSharedSuffixHostIsNeverRechecked() {
        when(reportedDomainRepository.findToRecheck(any())).thenReturn(List.of("evil.pages.dev", "a.com"));
        records(found("a.com", NAMESILO, "abuse@namesilo.com"));

        abuseReportService.recheck();

        verify(domainAgeService).records(List.of("a.com"), true);
        verify(reportedDomainRepository).markChecked(List.of("a.com"), NOW_UTC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP 429", "HTTP 503", "timed out", "bad json"})
    void aFailedRecheckNeverMarksADomainSuspended(final String failure) {
        when(reportedDomainRepository.findToRecheck(any())).thenReturn(List.of("omaehhm.com"));
        abuseReportService = service(realDomainAgeService(failure));

        abuseReportService.recheck();

        verify(reportedDomainRepository, never()).markSuspended(anyString(), any());
        verify(reportedDomainRepository, never()).markChecked(anyCollection(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aRecheck404FromTheRegistryMarksTheDomainSuspended() {
        pendingSince("omaehhm.com");
        when(reportedDomainRepository.findToRecheck(any())).thenReturn(List.of("omaehhm.com"));
        abuseReportService = service(realDomainAgeService("HTTP 404"));

        abuseReportService.recheck();

        verify(reportedDomainRepository).markSuspended("omaehhm.com", NOW_UTC);
        verify(eventPublisher).publishEvent(new DomainsSuspended(List.of("omaehhm.com")));
    }

    @Test
    void recheckStopsAfterRecheckDays() {
        when(settingsService.number(SettingKey.ABUSE_RECHECK_DAYS)).thenReturn(7);
        when(reportedDomainRepository.findToRecheck(NOW_UTC.minusDays(7))).thenReturn(List.of());

        abuseReportService.recheck();

        verify(reportedDomainRepository).findToRecheck(NOW_UTC.minusDays(7));
        verifyNoInteractions(domainAgeService, eventPublisher);
    }

    @Test
    void recheckLooksUpTwentyDomainsAtATime() {
        final var domains = IntStream.rangeClosed(1, 45).mapToObj(index -> "d" + index + ".com").toList();
        when(reportedDomainRepository.findToRecheck(any())).thenReturn(domains);
        when(domainAgeService.records(anyCollection(), anyBoolean())).thenAnswer(invocation -> invocation.<Collection<String>>getArgument(0).stream()
                .collect(Collectors.toMap(Function.identity(), domain -> new DomainRecord(domain, DomainRecord.Status.FOUND, null, null, null, List.of()))));

        abuseReportService.recheck();

        verify(domainAgeService).records(domains.subList(0, 20), true);
        verify(domainAgeService).records(domains.subList(20, 40), true);
        verify(domainAgeService).records(domains.subList(40, 45), true);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void newlySuspendedDomainsPostOneLine() {
        final var domains = IntStream.rangeClosed(1, 22).mapToObj(index -> "d" + index + ".com").toList();
        pendingSince(domains.toArray(String[]::new));
        when(reportedDomainRepository.findToRecheck(any())).thenReturn(domains);
        when(domainAgeService.records(anyCollection(), anyBoolean())).thenAnswer(invocation -> invocation.<Collection<String>>getArgument(0).stream()
                .collect(Collectors.toMap(Function.identity(), DomainRecord::notFound)));
        when(reportedDomainRepository.markSuspended("d22.com", NOW_UTC)).thenReturn(0);
        final var events = ArgumentCaptor.forClass(Object.class);

        abuseReportService.recheck();

        verify(eventPublisher).publishEvent(events.capture());
        final var suspended = (DomainsSuspended) events.getValue();
        assertThat(suspended.domains()).hasSize(21).doesNotContain("d22.com");
        appProperties.setFrontendUrl("https://app.hrva.cc");
        final var line = new DiscordAlertFormatter(appProperties, new ShortLinks(appProperties)).message(List.of(suspended), 0).content();
        assertThat(line).isEqualTo("Suspended since reported: " + String.join(", ", domains.subList(0, 20)) + " and 1 more <https://app.hrva.cc/admin/campaigns>");
    }

    @Test
    void nothingNewPostsNothing() {
        pendingSince("a.com");
        when(reportedDomainRepository.findToRecheck(any())).thenReturn(List.of("a.com"));
        records(DomainRecord.notFound("a.com"));
        when(reportedDomainRepository.markSuspended("a.com", NOW_UTC)).thenReturn(0);

        abuseReportService.recheck();

        verifyNoInteractions(eventPublisher);
        verify(reportedDomainRepository, never()).markChecked(anyCollection(), any());
    }

    private void pendingSince(final String... domains) {
        for (final var domain : domains) {
            pending.put(PENDING + domain, Duration.ofHours(36));
        }
    }

    private static JsonNode fixture(final String name) {
        try (var input = DefaultAbuseReportServiceTest.class.getResourceAsStream("/rdap/" + name)) {
            return JsonMapper.builder().build().readTree(input);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private DefaultAbuseReportService service(final DomainAgeService ageService) {
        return new DefaultAbuseReportService(campaignService, urlRepository, linkStatsRepository, reportedDomainRepository, ageService,
                new AbuseReportComposer(settingsService), settingsService, auditLogService, eventPublisher, redisTemplate, new AnalyticsDays(clock, appProperties), clock);
    }

    private DomainAgeService realDomainAgeService(final String failure) {
        final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> valueOperations = mock();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        final var fetcher = new PublicHttpFetcher(url -> true) {
            @Override
            public FetchResult fetch(final String url, final long maxBytes, final String etag, final String lastModified, final Duration requestTimeout) {
                if (url.startsWith("https://data.iana.org/")) {
                    return result("{\"services\": [[[\"com\"], [\"https://rdap.verisign.com/com/v1/\"]]]}");
                }
                if ("bad json".equals(failure)) {
                    return result("<html>Too many requests</html>");
                }
                throw new UrlValidationException(failure);
            }
        };
        return new DefaultDomainAgeService(fetcher, redisTemplate, JsonMapper.builder().build(), clock);
    }

    private static FetchResult result(final String body) {
        try {
            final var path = Files.createTempFile("rdap-recheck-", ".json");
            Files.writeString(path, body);
            return new FetchResult(false, path, null, null);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void link(final long id, final String longUrl, final String finalUrl, final LocalDateTime createDate) {
        links.put(id, new AbuseReportLink(id, longUrl, finalUrl, createDate, true));
    }

    private void records(final DomainRecord... records) {
        final var byDomain = new LinkedHashMap<String, DomainRecord>();
        for (final var record : records) {
            byDomain.put(record.domain(), record);
        }
        lenient().when(domainAgeService.records(anyCollection(), anyBoolean())).thenAnswer(invocation -> {
            final var answer = new LinkedHashMap<String, DomainRecord>();
            invocation.<Collection<String>>getArgument(0).forEach(domain -> answer.put(domain, byDomain.getOrDefault(domain, DomainRecord.failed(domain))));
            return answer;
        });
    }

    private static DomainRecord found(final String domain, final String registrar, final String abuseEmail) {
        return new DomainRecord(domain, DomainRecord.Status.FOUND, REGISTERED, registrar, abuseEmail, List.of("client transfer prohibited"));
    }

}
