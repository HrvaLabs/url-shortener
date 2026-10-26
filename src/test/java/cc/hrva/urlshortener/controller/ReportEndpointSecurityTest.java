package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.BioPageReviewService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ReportEndpointSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class ReportEndpointSecurityTest {

    private static final String REPORTS = "/api/v1/reports";
    private static final String REPORT = """
            {"shortUrl":"v0DB3","category":"PHISHING","details":"asks for my bank login"}""";
    private static final int BODY_LIMIT = 64 * 1024;
    private static final String REQUEST_TOO_LARGE = "{\"error\":\"request_too_large\"}";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReviewQueueService reviewQueueService;

    @MockitoBean
    private UrlRepository urlRepository;

    @MockitoBean
    private BioPageRepository bioPageRepository;

    @MockitoBean
    private BioPageReviewService bioPageReviewService;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), eq(Duration.ofHours(1)))).thenReturn(true);
    }

    @Test
    void anyoneCanReportALinkWithoutLoggingIn() throws Exception {
        final var url = Url.builder().id(287L).shortUrl("v0DB3").longUrl("https://findfavoritedate.life/?u=f6yp608").active(true).build();
        when(urlRepository.findByShortUrlAndActiveTrue("v0DB3")).thenReturn(Optional.of(url));

        mockMvc.perform(post(REPORTS).contentType(MediaType.APPLICATION_JSON).content(REPORT))
                .andExpect(status().isAccepted());

        verify(reviewQueueService).report(url, ReportCategory.PHISHING, "asks for my bank login");
    }

    @Test
    void anyoneCanReportABioPageWithoutLoggingIn() throws Exception {
        final var page = BioPage.builder().id(12L).handle("ana").owner(User.builder().id(7L).active(true).build()).build();
        when(bioPageRepository.findByHandleWithOwner("ana")).thenReturn(Optional.of(page));

        for (final var target : List.of("@Ana", "http://localhost:3000/bio/ana")) {
            mockMvc.perform(post(REPORTS).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"shortUrl\":\"%s\",\"category\":\"SPAM\"}".formatted(target)))
                    .andExpect(status().isAccepted())
                    .andExpect(content().string(""));
        }

        verify(bioPageReviewService, times(2)).report(page, ReportCategory.SPAM, null);
        verifyNoInteractions(reviewQueueService);
    }

    @Test
    void anUnreadableTargetIs400WithTheNewText() throws Exception {
        mockMvc.perform(post(REPORTS).contentType(MediaType.APPLICATION_JSON).content("{\"shortUrl\":\"@a\",\"category\":\"SPAM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Enter a hrva.cc short link or bio page, like hrva.cc/abc or hrva.cc/@name\"}", JsonCompareMode.STRICT));

        verifyNoInteractions(rateLimiter, bioPageRepository, bioPageReviewService, urlRepository, reviewQueueService);
    }

    @Test
    void onlyPostingAReportIsOpen() throws Exception {
        mockMvc.perform(get(REPORTS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(REPORTS).contentType(MediaType.APPLICATION_JSON).content(REPORT))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(REPORTS + "/v0DB3").contentType(MediaType.APPLICATION_JSON).content(REPORT))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reviewQueueService);
    }

    @Test
    void anOversizedReportIsRefusedBeforeItIsRead() throws Exception {
        mockMvc.perform(post(REPORTS).contentType(MediaType.APPLICATION_JSON).content(padded(REPORT, BODY_LIMIT + 1)))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().string(REQUEST_TOO_LARGE));

        verifyNoInteractions(rateLimiter, urlRepository, reviewQueueService);
    }

    @Test
    void anOversizedReportOfUnknownLengthIsRefusedOnceItPassesTheLimit() throws Exception {
        mockMvc.perform(post(REPORTS).contentType(MediaType.APPLICATION_JSON).content(padded(REPORT, BODY_LIMIT + 1)).with(unknownLength()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rateLimiter, urlRepository, reviewQueueService);
    }

    @Test
    void aReportAtTheLimitStillWorks() throws Exception {
        for (final var request : List.of(
                post(REPORTS).contentType(MediaType.APPLICATION_JSON).content(padded(REPORT, BODY_LIMIT)),
                post(REPORTS).contentType(MediaType.APPLICATION_JSON).content(padded(REPORT, BODY_LIMIT)).with(unknownLength()))) {
            mockMvc.perform(request).andExpect(status().isAccepted());
        }
    }

    private static String padded(final String json, final int length) {
        return json + " ".repeat(length - json.length());
    }

    private static RequestPostProcessor unknownLength() {
        return request -> {
            final var chunked = spy(request);
            doReturn(-1).when(chunked).getContentLength();
            doReturn(-1L).when(chunked).getContentLengthLong();
            return chunked;
        };
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (final JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            ReportController.class
    })
    static class TestApplication {
    }

}
