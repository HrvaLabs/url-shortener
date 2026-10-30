package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkReportDto;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageReview;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.DomainRouteRow;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.BioPageReviewService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.domain.CustomDomainRegistry;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReportControllerTest {

    private static final String REPORTS = "/api/v1/reports";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String SHORT = "v0DB3";
    private static final String DETAILS = "asks for my bank login";
    private static final String TOO_MANY_REPORTS = "You've sent a lot of reports in a short time. Try again in an hour.";
    private static final String TARGET_INVALID = "Enter a hrva.cc short link or bio page, like hrva.cc/abc or hrva.cc/@name";

    private MockMvc mockMvc;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private BioPageRepository bioPageRepository;

    @Mock
    private BioPageReviewService bioPageReviewService;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private CustomDomainRepository customDomainRepository;

    @Mock
    private HttpServletRequest request;

    private final AppProperties appProperties = new AppProperties();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Integer> attempts = new HashMap<>();

    private final List<Duration> windows = new ArrayList<>();

    private final Url url = Url.builder().id(287L).shortUrl(SHORT).longUrl("https://findfavoritedate.life/?u=f6yp608").active(true).build();

    private final BioPage page = BioPage.builder().id(12L).handle("ana").owner(User.builder().id(7L).active(true).build()).build();

    private CustomDomainRegistry customDomainRegistry;

    @BeforeEach
    void setUp() {
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("http://localhost:3000");
        lenient().when(clientIpResolver.getClientIp(request)).thenReturn(CLIENT_IP);
        lenient().when(customDomainRepository.findRoutes()).thenReturn(List.of(
                new DomainRouteRow(7L, "go.example.com", "go.example.com", CustomDomainStatus.ACTIVE, 5L, true),
                new DomainRouteRow(8L, "www.example.com", "www.example.com", CustomDomainStatus.ACTIVE, 5L, true),
                new DomainRouteRow(9L, "off.example.com", "off.example.com", CustomDomainStatus.DISABLED, 5L, true),
                new DomainRouteRow(10L, "xn--g-1tb.example.com", "xn--g-1tb.example.com", CustomDomainStatus.ACTIVE, 6L, false)));
        customDomainRegistry = new CustomDomainRegistry(customDomainRepository,
                new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:report-controller")));
        customDomainRegistry.reload();
        mockMvc = mockMvcWith((key, window) -> {
            windows.add(window);
            return attempts.merge(key, 1, Integer::sum);
        });
    }

    private MockMvc mockMvcWith(final RateLimiter rateLimiter) {
        return MockMvcBuilders.standaloneSetup(new ReportController(reviewQueueService, urlRepository, bioPageRepository, bioPageReviewService, rateLimiter,
                        clientIpResolver, request, appProperties, customDomainRegistry))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static DataIntegrityViolationException integrityViolation(final String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", new SQLException("violates constraint"), constraint));
    }

    private ResultActions report(final String shortUrl, final String category, final String details) throws Exception {
        return mockMvc.perform(post(REPORTS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(LinkReportDto.builder()
                        .shortUrl(shortUrl)
                        .category(category)
                        .details(details)
                        .build())));
    }

    @Test
    void aReportOnAnActiveLinkGoesToTheReviewQueue() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "PHISHING", DETAILS)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(reviewQueueService).report(url, ReportCategory.PHISHING, DETAILS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hrva.cc/v0DB3", "https://hrva.cc/v0DB3", "HTTP://www.HRVA.cc/v0DB3/", "hrva.cc/v0DB3?utm_source=mail", " /v0DB3 "})
    void theShortLinkCanBeGivenAsALinkOnTheShortHost(final String shortUrl) throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));

        report(shortUrl, "SPAM", null).andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.SPAM, null);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "https://bit.ly/v0DB3", "hrva.cc", "hrva.cc/", "hrva.cc/v0DB3/stats", "v0DB3.html", "abcdefghijklmnopqrstuvwxyz0123456", "hrva.cc/abcdefghijklmnopqrstuvwxyz0123456"})
    void anythingButAShortLinkIsRefused(final String shortUrl) throws Exception {
        report(shortUrl, "PHISHING", DETAILS)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(TARGET_INVALID));

        verifyNoInteractions(urlRepository, reviewQueueService, bioPageRepository, bioPageReviewService);
        assertThat(attempts).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"@Ana", "@ana", " hrva.cc/@ana ", "hrva.cc/@ANA/", "https://www.hrva.cc/@ana?x#y", "HTTPS://HRVA.CC/@ana#top",
            "http://localhost:3000/bio/ana", "localhost:3000/bio/Ana?utm_source=x", "http://localhost:3000/bio/ana/"})
    void aBioPageIsReportedByAt(final String target) throws Exception {
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));

        report(target, "SPAM", "fake shop")
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(bioPageReviewService).report(page, ReportCategory.SPAM, "fake shop");
        verifyNoInteractions(urlRepository, reviewQueueService);
    }

    @Test
    void aHiddenBioPageIsAcceptedButNotQueued() throws Exception {
        page.setHiddenAt(LocalDateTime.of(2026, 10, 26, 9, 0));
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));

        report("@ana", "SPAM", null).andExpect(status().isAccepted()).andExpect(content().string(""));

        verifyNoInteractions(bioPageReviewService, reviewQueueService);
    }

    @Test
    void aBioPageOfADeactivatedOwnerIsAcceptedButNotQueued() throws Exception {
        page.getOwner().setActive(false);
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));

        report("@ana", "SPAM", null).andExpect(status().isAccepted());

        verifyNoInteractions(bioPageReviewService);
    }

    @Test
    void anUnknownHandleIsStill202() throws Exception {
        when(bioPageRepository.findByHandleWithOwner("nobody")).thenReturn(Optional.empty());

        report("hrva.cc/@nobody", "PHISHING", DETAILS)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verifyNoInteractions(bioPageReviewService, reviewQueueService, urlRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"@", "@a", "@ab", "@@ana", "@ana!", "@---", "@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "hrva.cc/@", "hrva.cc/@ana/blog",
            "http://localhost:3000/bio/", "http://localhost:3000/bio/a", "http://localhost:3000/bio/ana/extra", "http://localhost:3000/ana",
            "http://localhost:3000/@ana", "hrva.cc/bio/ana", "https://evil.example/@ana", "https://evil.example/bio/ana"})
    void aMalformedTargetIs400WithTheNewText(final String target) throws Exception {
        report(target, "SPAM", DETAILS)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(TARGET_INVALID));

        verifyNoInteractions(urlRepository, reviewQueueService, bioPageRepository, bioPageReviewService);
        assertThat(attempts).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"go.example.com/menu", "https://go.example.com/menu", "HTTPS://GO.Example.COM./menu/", "go.example.com/menu?utm_source=mail#top",
            " http://go.example.com/menu "})
    void aLinkOnACustomDomainIsReportedOnThatDomain(final String target) throws Exception {
        final var onDomain = domainLink(7L, "go.example.com");
        when(urlRepository.findActive(7L, "menu")).thenReturn(Optional.of(onDomain));

        report(target, "PHISHING", null).andExpect(status().isAccepted());

        verify(reviewQueueService).report(onDomain, ReportCategory.PHISHING, null);
        verify(urlRepository, never()).findActive(null, "menu");
    }

    @Test
    void aLinkOnATurnedOffDomainCanStillBeReported() throws Exception {
        final var onDomain = domainLink(9L, "off.example.com");
        when(urlRepository.findActive(9L, "menu")).thenReturn(Optional.of(onDomain));

        report("off.example.com/menu", "SPAM", null).andExpect(status().isAccepted());

        verify(reviewQueueService).report(onDomain, ReportCategory.SPAM, null);
    }

    @Test
    void aCustomHostIsLookedUpBeforeWwwIsStripped() throws Exception {
        final var onDomain = domainLink(8L, "www.example.com");
        when(urlRepository.findActive(8L, "menu")).thenReturn(Optional.of(onDomain));

        report("https://www.example.com/menu", "SPAM", null).andExpect(status().isAccepted());

        verify(reviewQueueService).report(onDomain, ReportCategory.SPAM, null);
        verify(urlRepository, never()).findActive(null, "menu");
    }

    @Test
    void wwwBeforeACustomHostIsAnotherHost() throws Exception {
        report("https://www.go.example.com/menu", "SPAM", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(TARGET_INVALID));

        verifyNoInteractions(urlRepository, reviewQueueService);
    }

    @Test
    void wwwIsStillStrippedFromTheShortHost() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));

        report("https://www.hrva.cc/" + SHORT, "SPAM", null).andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.SPAM, null);
    }

    @Test
    void aUnicodeCustomHostIsReadAsPunycode() throws Exception {
        final var onDomain = domainLink(10L, "xn--g-1tb.example.com");
        when(urlRepository.findActive(10L, "menu")).thenReturn(Optional.of(onDomain));

        report("https://g" + Character.toString(0x43E) + ".example.com/menu", "SPAM", null).andExpect(status().isAccepted());

        verify(reviewQueueService).report(onDomain, ReportCategory.SPAM, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"go.example.com", "go.example.com/", "go.example.com/@ana", "go.example.com/menu/stats", "go.example.com/bio/ana",
            "go.example.com/menu.html", "go.example.com/abcdefghijklmnopqrstuvwxyz0123456"})
    void onACustomHostOnlyAShortCodeCanBeReported(final String target) throws Exception {
        report(target, "SPAM", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(TARGET_INVALID));

        verifyNoInteractions(urlRepository, reviewQueueService, bioPageRepository, bioPageReviewService);
    }

    @Test
    void anUnknownLinkOnACustomDomainIsStill202() throws Exception {
        report("go.example.com/nope", "SPAM", null).andExpect(status().isAccepted());

        verify(urlRepository).findActive(7L, "nope");
        verifyNoInteractions(reviewQueueService);
    }

    private static Url domainLink(final long domainId, final String hostname) {
        return Url.builder().id(500L + domainId).shortUrl("menu").longUrl("https://evil.example/").active(true)
                .domain(CustomDomain.builder().id(domainId).hostname(hostname).build()).build();
    }

    @Test
    void aShortLinkStillReportsTheLink() throws Exception {
        when(urlRepository.findActive(null, "ana")).thenReturn(Optional.of(url));

        report("hrva.cc/ana", "SPAM", null).andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.SPAM, null);
        verifyNoInteractions(bioPageRepository, bioPageReviewService);
    }

    @Test
    void bioPageAndLinkReportsShareOneLimit() throws Exception {
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));
        for (var sent = 0; sent < 5; sent++) {
            report("@ana", "SPAM", null).andExpect(status().isAccepted());
        }

        report(SHORT, "PHISHING", DETAILS)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY_REPORTS));
        report("@ana", "SPAM", null).andExpect(status().isTooManyRequests());

        verify(bioPageReviewService, times(5)).report(page, ReportCategory.SPAM, null);
        verifyNoInteractions(urlRepository, reviewQueueService);
        assertThat(attempts).containsOnlyKeys(RateLimiter.clientKey("report", CLIENT_IP));
    }

    @Test
    void impersonationCanBeReportedForALinkAndForABioPage() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));

        report(SHORT, "IMPERSONATION", DETAILS).andExpect(status().isAccepted());
        report("@ana", "IMPERSONATION", "pretends to be me").andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.IMPERSONATION, DETAILS);
        verify(bioPageReviewService).report(page, ReportCategory.IMPERSONATION, "pretends to be me");
    }

    @Test
    void aBioPageReportIsStillTakenWhenTheRateLimiterIsUnavailable() throws Exception {
        mockMvc = mockMvcWith((key, window) -> {
            throw new RateLimiterUnavailableException(new IllegalStateException("redis down"));
        });
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));

        report("@ana", "OTHER", "pretends to be me").andExpect(status().isAccepted());

        verify(bioPageReviewService).report(page, ReportCategory.OTHER, "pretends to be me");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aReportOnABioPageDeletedMeanwhileIsStill202(final CapturedOutput output) throws Exception {
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));
        doThrow(integrityViolation("fk_bio_page_review_page")).when(bioPageReviewService).report(page, ReportCategory.SPAM, null);

        report("@ana", "SPAM", null)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(output).contains("Visitor report dropped because the bio page was deleted meanwhile pageId=12");
    }

    @Test
    void anotherIntegrityErrorOnABioPageReportIsNotHidden() throws Exception {
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));
        doThrow(integrityViolation("pk_bio_page_review")).when(bioPageReviewService).report(page, ReportCategory.SPAM, null);

        report("@ana", "SPAM", null).andExpect(status().isInternalServerError());
    }

    @Test
    void anIntegrityErrorWithoutAConstraintNameOnABioPageReportIsNotHidden() throws Exception {
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));
        doThrow(new DataIntegrityViolationException("fk_bio_page_review_page")).when(bioPageReviewService).report(page, ReportCategory.SPAM, null);

        report("@ana", "SPAM", null).andExpect(status().isInternalServerError());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aReportExtendingAReviewDeletedWithItsPageMeanwhileIsStill202(final CapturedOutput output) throws Exception {
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));
        doThrow(new ObjectOptimisticLockingFailureException(BioPageReview.class, 31L)).when(bioPageReviewService).report(page, ReportCategory.SPAM, null);
        when(bioPageRepository.existsById(12L)).thenReturn(false);

        report("@ana", "SPAM", null)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        assertThat(output).contains("Visitor report dropped because the bio page was deleted meanwhile pageId=12");
    }

    @Test
    void anOptimisticLockFailureOnABioPageThatStillExistsIsNotHidden() throws Exception {
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));
        doThrow(new ObjectOptimisticLockingFailureException(BioPageReview.class, 31L)).when(bioPageReviewService).report(page, ReportCategory.SPAM, null);
        when(bioPageRepository.existsById(12L)).thenReturn(true);

        report("@ana", "SPAM", null).andExpect(status().isInternalServerError());
    }

    @Test
    void anOptimisticLockFailureOnALinkReportIsNotHidden() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));
        doThrow(new ObjectOptimisticLockingFailureException(Url.class, 287L)).when(reviewQueueService).report(url, ReportCategory.SPAM, null);

        report(SHORT, "SPAM", null).andExpect(status().isInternalServerError());

        verifyNoInteractions(bioPageRepository);
    }

    @Test
    void aBioPageReportNeverChangesThePage() throws Exception {
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));

        report("@ana", "SPAM", null).andExpect(status().isAccepted());

        verify(bioPageRepository).findByHandleWithOwner("ana");
        verifyNoMoreInteractions(bioPageRepository);
        assertThat(page.getHiddenAt()).isNull();
        assertThat(page.getHandle()).isEqualTo("ana");
    }

    @Test
    void aShortCodeCanBeAsLongAsACustomAlias() throws Exception {
        final var longest = "abcdefghijklmnopqrstuvwxyz012345";
        when(urlRepository.findActive(null, longest)).thenReturn(Optional.empty());

        report("hrva.cc/" + longest, "SPAM", null).andExpect(status().isAccepted());

        verify(urlRepository).findActive(null, longest);
    }

    @Test
    void anUnknownOrInactiveLinkIsAcceptedButNothingIsWritten() throws Exception {
        when(urlRepository.findActive(null, "gone")).thenReturn(Optional.empty());

        report("gone", "MALWARE", DETAILS)
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verifyNoInteractions(reviewQueueService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"SCAM", "phishing"})
    void theCategoryMustBeOneOfTheFive(final String category) throws Exception {
        report(SHORT, category, DETAILS)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Category must be PHISHING, MALWARE, SPAM, IMPERSONATION or OTHER"));

        verifyNoInteractions(urlRepository, reviewQueueService);
    }

    @Test
    void detailsOverFiveHundredCharactersAreRefused() throws Exception {
        report(SHORT, "OTHER", "a".repeat(501))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Details must be 500 characters or fewer"));

        verifyNoInteractions(urlRepository, reviewQueueService);
        assertThat(attempts).isEmpty();
    }

    @Test
    void fiveHundredCharactersOfDetailsAreCountedAfterTrimming() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "OTHER", "  " + "a".repeat(500) + "\u0007\n ").andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.OTHER, "a".repeat(500));
    }

    @Test
    void detailsAreTrimmedAndStrippedOfControlCharacters() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "PHISHING", "  asks for\u0000 my\u001b bank\r\nlogin\u0085 ").andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.PHISHING, DETAILS);
    }

    @Test
    void detailsAreStrippedOfFormatCharactersSoTheyCannotReorderOrHideText() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "PHISHING", "\u202Easks for\u200B my\u2066 bank\u200D login\uFEFF").andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.PHISHING, DETAILS);
    }

    @Test
    void blankDetailsCountAsNoDetails() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "SPAM", " \t\u0000 ").andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.SPAM, null);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aMalformedBodyIsRefusedWithoutLoggingAStackTrace(final CapturedOutput output) throws Exception {
        for (final var body : List.of("{\"shortUrl\":\"v0DB3\",", "", "{\"shortUrl\":{\"nested\":true},\"category\":\"SPAM\"}")) {
            mockMvc.perform(post(REPORTS).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Malformed request body"));
        }

        verifyNoInteractions(urlRepository, reviewQueueService);
        assertThat(attempts).isEmpty();
        assertThat(output).doesNotContain("Unhandled exception").doesNotContain("\tat ");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aBodyThatIsNotJsonIsAnUnsupportedMediaType(final CapturedOutput output) throws Exception {
        mockMvc.perform(post(REPORTS).contentType(MediaType.TEXT_PLAIN).content("hrva.cc/v0DB3 is phishing"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string(HttpHeaders.ACCEPT, containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.error").value("Unsupported media type"));

        verifyNoInteractions(urlRepository, reviewQueueService);
        assertThat(output).doesNotContain("Unhandled exception").doesNotContain("\tat ");
    }

    @Test
    void theSixthReportFromOneClientWithinAnHourIsRefused() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));

        for (var sent = 0; sent < 5; sent++) {
            report(SHORT, "PHISHING", DETAILS).andExpect(status().isAccepted());
        }
        report(SHORT, "PHISHING", DETAILS)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(TOO_MANY_REPORTS));

        verify(reviewQueueService, times(5)).report(url, ReportCategory.PHISHING, DETAILS);
        assertThat(windows).hasSize(6).containsOnly(Duration.ofHours(1));
    }

    @Test
    void theLimitIsKeptPerHashedClientAddress() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));
        for (var sent = 0; sent < 5; sent++) {
            report(SHORT, "PHISHING", DETAILS).andExpect(status().isAccepted());
        }
        when(clientIpResolver.getClientIp(request)).thenReturn("198.51.100.20");

        report(SHORT, "PHISHING", DETAILS).andExpect(status().isAccepted());

        assertThat(attempts).containsOnlyKeys(RateLimiter.clientKey("report", CLIENT_IP), RateLimiter.clientKey("report", "198.51.100.20"));
        assertThat(attempts.keySet()).noneMatch(key -> key.contains(CLIENT_IP) || key.contains("198.51.100.20"));
    }

    @Test
    void reportsOnUnknownLinksCountTowardsTheLimitSoTheLimitShowsNothingAboutLinks() throws Exception {
        for (var sent = 0; sent < 5; sent++) {
            report("gone" + sent, "SPAM", null).andExpect(status().isAccepted());
        }

        report(SHORT, "PHISHING", DETAILS).andExpect(status().isTooManyRequests());

        verify(urlRepository, never()).findActive(null, SHORT);
        verifyNoInteractions(reviewQueueService);
    }

    @Test
    void aReportIsStillTakenWhenTheRateLimiterIsUnavailable() throws Exception {
        mockMvc = mockMvcWith((key, window) -> {
            throw new RateLimiterUnavailableException(new IllegalStateException("redis down"));
        });
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "PHISHING", DETAILS).andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.PHISHING, DETAILS);
    }

    @Test
    void aReportNeverChangesTheLink() throws Exception {
        when(urlRepository.findActive(null, SHORT)).thenReturn(Optional.of(url));

        report(SHORT, "PHISHING", DETAILS).andExpect(status().isAccepted());

        verify(urlRepository).findActive(null, SHORT);
        verifyNoMoreInteractions(urlRepository);
        verify(reviewQueueService).report(any(Url.class), any(ReportCategory.class), any());
        verifyNoMoreInteractions(reviewQueueService);
        assertThat(url.isActive()).isTrue();
        assertThat(url.getBlockReason()).isNull();
        assertThat(url.getBlockDetail()).isNull();
    }

}
