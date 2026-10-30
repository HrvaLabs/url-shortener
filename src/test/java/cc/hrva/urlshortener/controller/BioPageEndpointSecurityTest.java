package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.dto.bio.BioHandleAvailabilityResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioPageAnalyticsResponse;
import cc.hrva.urlshortener.dto.bio.BioPageButtonDto;
import cc.hrva.urlshortener.dto.bio.BioPageCreateDto;
import cc.hrva.urlshortener.dto.bio.BioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioPageUpdateDto;
import cc.hrva.urlshortener.dto.bio.BioViewDay;
import cc.hrva.urlshortener.dto.bio.PublicBioPageResponse;
import cc.hrva.urlshortener.exception.BioPageConflictException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.exception.InvalidAnalyticsRangeException;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioIcon;
import cc.hrva.urlshortener.model.enums.BioLinkState;
import cc.hrva.urlshortener.model.enums.BioTheme;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.AnalyticsService;
import cc.hrva.urlshortener.service.BioPageAnalyticsService;
import cc.hrva.urlshortener.service.BioPageService;
import cc.hrva.urlshortener.service.PublicBioPageService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = BioPageEndpointSecurityTest.TestApplication.class,
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
class BioPageEndpointSecurityTest {

    private static final String MY_PAGE = "/api/v1/bio-pages/me";
    private static final String MY_HANDLE = MY_PAGE + "/handle";
    private static final String AVAILABILITY = "/api/v1/bio-pages/handles/ana/availability";
    private static final String PUBLIC_PAGE = "/api/v1/public/bio-pages/ana";
    private static final String VIEW_BEACON = PUBLIC_PAGE + "/views";
    private static final String BIO_ANALYTICS = "/api/v1/analytics/bio-page";
    private static final String NOT_AVAILABLE = "This page isn't available.";
    private static final String USER = "ana@example.com";
    private static final String USER_TOKEN = "user-token";
    private static final String NO_PAGE = "You don't have a bio page yet.";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BioPageService bioPageService;

    @MockitoBean
    private PublicBioPageService publicBioPageService;

    @MockitoBean
    private BioPageAnalyticsService bioPageAnalyticsService;

    @MockitoBean
    private AnalyticsService analyticsService;

    @MockitoBean
    private UrlRepository urlRepository;

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

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        lenient().when(tokenProvider.validateToken(USER_TOKEN)).thenReturn(true);
        lenient().when(tokenProvider.getAuthentication(USER_TOKEN))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(USER, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        lenient().when(userRepository.findByEmail(USER)).thenReturn(Optional.of(User.builder().email(USER).active(true).build()));
    }

    @Test
    void theOwnerApiNeedsSignIn() throws Exception {
        mockMvc.perform(get(MY_PAGE))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(MY_PAGE).contentType(MediaType.APPLICATION_JSON).content("{\"handle\":\"ana\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(MY_PAGE).contentType(MediaType.APPLICATION_JSON).content(page("[]")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(MY_HANDLE).contentType(MediaType.APPLICATION_JSON).content("{\"handle\":\"ana\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(MY_PAGE))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(AVAILABILITY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(bioPageService);
    }

    @Test
    void aSignedInUserReadsTheirPage() throws Exception {
        when(bioPageService.mine()).thenReturn(ana());

        mockMvc.perform(signedIn(get(MY_PAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle").value("ana"))
                .andExpect(jsonPath("$.avatarEmoji").value("x"))
                .andExpect(jsonPath("$.buttons[0].shortUrl").value("ana-ig"))
                .andExpect(jsonPath("$.buttons[0].linkState").value("LIVE"))
                .andExpect(jsonPath("$.buttons[0].shownOnPage").value(true))
                .andExpect(jsonPath("$.createdAt").value("2026-10-26T09:00:00"));
    }

    @Test
    void aMissingPageIs404WithItsText() throws Exception {
        when(bioPageService.mine()).thenThrow(new BioPageNotFoundException(NO_PAGE));

        mockMvc.perform(signedIn(get(MY_PAGE)))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"You don't have a bio page yet.\"}", true));
    }

    @Test
    void creatingAPageIs201() throws Exception {
        when(bioPageService.create(new BioPageCreateDto("Ana"))).thenReturn(ana());

        mockMvc.perform(signedIn(post(MY_PAGE)).content("{\"handle\":\"Ana\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.handle").value("ana"));
    }

    @Test
    void savingChangingTheHandleAndCheckingAnswer200() throws Exception {
        when(bioPageService.update(any(BioPageUpdateDto.class))).thenReturn(ana());
        when(bioPageService.changeHandle(new BioHandleDto("ana"))).thenReturn(ana());
        when(bioPageService.availability("ana")).thenReturn(new BioHandleAvailabilityResponse("ana", true, null));

        mockMvc.perform(signedIn(put(MY_PAGE)).content(page("[" + button("\"Instagram\"") + "]")))
                .andExpect(status().isOk());
        mockMvc.perform(signedIn(put(MY_HANDLE)).content("{\"handle\":\"ana\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(signedIn(get(AVAILABILITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void deletingAPageIs204() throws Exception {
        mockMvc.perform(signedIn(delete(MY_PAGE)))
                .andExpect(status().isNoContent());

        verify(bioPageService).delete();
    }

    @Test
    void aSaveThatLostARaceIs409WithItsText() throws Exception {
        when(bioPageService.update(any(BioPageUpdateDto.class))).thenThrow(new BioPageConflictException("This page changed. Reload and try again."));

        mockMvc.perform(signedIn(put(MY_PAGE)).content(page("[" + button("\"Instagram\"") + "]")))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"This page changed. Reload and try again.\"}", true));
    }

    @Test
    void aDeleteThatLostARaceIs404WithItsText() throws Exception {
        doThrow(new BioPageNotFoundException(NO_PAGE)).when(bioPageService).delete();

        mockMvc.perform(signedIn(delete(MY_PAGE)))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"You don't have a bio page yet.\"}", true));
    }

    @Test
    void deletingAHiddenPageIs409WithItsText() throws Exception {
        doThrow(new BioPageConflictException("An admin hid this page, so it can't be deleted here. You can still delete your whole account in Settings."))
                .when(bioPageService).delete();

        mockMvc.perform(signedIn(delete(MY_PAGE)))
                .andExpect(status().isConflict())
                .andExpect(content().json(
                        "{\"error\":\"An admin hid this page, so it can't be deleted here. You can still delete your whole account in Settings.\"}", true));
    }

    @Test
    void theHandleRedirectIsPublic() throws Exception {
        mockMvc.perform(get("/@ana"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/bio/ana"));
    }

    @Test
    void thePublicReadNeedsNoSignIn() throws Exception {
        when(publicBioPageService.find("ana")).thenReturn(publicAna());

        mockMvc.perform(get(PUBLIC_PAGE))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {"handle":"ana","displayName":"Ana Horvat","bio":"Photos from Zagreb","avatarKind":"EMOJI","avatarEmoji":"x",
                        "accent":"TEAL","theme":"AUTO","indexable":false,"buttons":[{"title":"Instagram","icon":"INSTAGRAM","shortUrl":"ana-ig"}]}""",
                        true));
    }

    @Test
    void aSignedInVisitorReadsThePublicPageToo() throws Exception {
        when(publicBioPageService.find("ana")).thenReturn(publicAna());

        mockMvc.perform(signedIn(get(PUBLIC_PAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle").value("ana"));
    }

    @Test
    void thePathIsPassedAsTyped() throws Exception {
        when(publicBioPageService.find("@Ana")).thenReturn(publicAna());

        mockMvc.perform(get("/api/v1/public/bio-pages/@Ana"))
                .andExpect(status().isOk());

        verify(publicBioPageService).find("@Ana");
    }

    @Test
    void anUnavailablePageIs404WithItsText() throws Exception {
        when(publicBioPageService.find("ana")).thenThrow(new BioPageNotFoundException(NOT_AVAILABLE));

        mockMvc.perform(get(PUBLIC_PAGE))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(content().json("{\"error\":\"This page isn't available.\"}", true));
    }

    @Test
    void theBeaconIsPublicAndAlways204() throws Exception {
        doThrow(new IllegalStateException("boom")).when(publicBioPageService).recordView(anyString(), any(ClickRequest.class));

        mockMvc.perform(post(VIEW_BEACON))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(post("/api/v1/public/bio-pages/nobody/views"))
                .andExpect(status().isNoContent());
    }

    @Test
    void theBeaconPassesTheHandleAndTheVisitor() throws Exception {
        final var click = ArgumentCaptor.forClass(ClickRequest.class);

        mockMvc.perform(post("/api/v1/public/bio-pages/Ana/views")
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 Test")
                        .header(HttpHeaders.REFERER, "https://app.hrva.cc/bio/ana"))
                .andExpect(status().isNoContent());

        verify(publicBioPageService).recordView(eq("Ana"), click.capture());
        assertThat(click.getValue().userAgent()).isEqualTo("Mozilla/5.0 Test");
        assertThat(click.getValue().referer()).isEqualTo("https://app.hrva.cc/bio/ana");
        assertThat(click.getValue().clientIp()).isEqualTo("127.0.0.1");
    }

    @Test
    void aSignedInVisitorsViewIsCountedToo() throws Exception {
        mockMvc.perform(signedIn(post(VIEW_BEACON)))
                .andExpect(status().isNoContent());

        verify(publicBioPageService).recordView(eq("ana"), any(ClickRequest.class));
    }

    @Test
    void bioAnalyticsNeedsSignIn() throws Exception {
        mockMvc.perform(get(BIO_ANALYTICS + "?days=7"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(bioPageAnalyticsService);
    }

    @Test
    void aSignedInUserReadsTheirBioAnalytics() throws Exception {
        final var day = LocalDate.of(2026, 10, 26);
        when(bioPageAnalyticsService.analytics(7)).thenReturn(new BioPageAnalyticsResponse(7, day.minusDays(6), day, "ana", 12, 3,
                List.of(new BioViewDay(day, 12, 3)),
                List.of(new BioPageAnalyticsResponse.ButtonClicks(31L, 41L, "ana-ig", "Instagram", true, BioLinkState.LIVE, true, 9, 14),
                        new BioPageAnalyticsResponse.ButtonClicks(32L, 42L, "ana-old", "Old", false, BioLinkState.EXPIRED, false, 0, 2))));

        mockMvc.perform(signedIn(get(BIO_ANALYTICS + "?days=7")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"days":7,"from":"2026-10-20","to":"2026-10-26","handle":"ana","views":12,"botViews":3,
                        "daily":[{"date":"2026-10-26","views":12,"botViews":3}],
                        "buttons":[{"buttonId":31,"urlId":41,"shortUrl":"ana-ig","title":"Instagram","visible":true,"linkState":"LIVE",
                        "shownOnPage":true,"fromHrvaPages":9,"allClicks":14},
                        {"buttonId":32,"urlId":42,"shortUrl":"ana-old","title":"Old","visible":false,"linkState":"EXPIRED",
                        "shownOnPage":false,"fromHrvaPages":0,"allClicks":2}]}""",
                        true));
    }

    @Test
    void bioAnalyticsRangeIs400() throws Exception {
        when(bioPageAnalyticsService.analytics(14)).thenThrow(new InvalidAnalyticsRangeException("days must be 7, 30, 90 or 365"));

        mockMvc.perform(signedIn(get(BIO_ANALYTICS + "?days=14")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"days must be 7, 30, 90 or 365\"}", true));
    }

    @Test
    void bioAnalyticsWithoutAPageIs404WithItsText() throws Exception {
        when(bioPageAnalyticsService.analytics(null)).thenThrow(new BioPageNotFoundException(NO_PAGE));

        mockMvc.perform(signedIn(get(BIO_ANALYTICS)))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"You don't have a bio page yet.\"}", true));
    }

    @Test
    void theSwitchesMayBeLeftOut() throws Exception {
        when(bioPageService.update(any(BioPageUpdateDto.class))).thenReturn(ana());

        mockMvc.perform(signedIn(put(MY_PAGE)).content("{\"displayName\":\"Ana\",\"avatarKind\":\"INITIALS\",\"accent\":\"TEAL\",\"theme\":\"AUTO\","
                        + "\"buttons\":[{\"urlId\":41,\"title\":\"Instagram\"},{\"urlId\":42,\"title\":\"Blog\",\"visible\":false}]}"))
                .andExpect(status().isOk());

        final var saved = ArgumentCaptor.forClass(BioPageUpdateDto.class);
        verify(bioPageService).update(saved.capture());
        assertThat(saved.getValue().getIndexable()).isNull();
        assertThat(saved.getValue().getButtons()).extracting(BioPageButtonDto::getVisible).containsExactly(null, false);
    }

    @Test
    void theCopyTimestampIsReadInTheFormatThePageIsSentIn() throws Exception {
        when(bioPageService.update(any(BioPageUpdateDto.class))).thenReturn(ana());

        mockMvc.perform(signedIn(put(MY_PAGE)).content("{\"displayName\":\"Ana\",\"avatarKind\":\"INITIALS\",\"accent\":\"TEAL\",\"theme\":\"AUTO\","
                        + "\"buttons\":[],\"expectedUpdatedAt\":\"2026-10-26T09:05:00.1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").value("2026-10-26T09:05:00"));

        final var saved = ArgumentCaptor.forClass(BioPageUpdateDto.class);
        verify(bioPageService).update(saved.capture());
        assertThat(saved.getValue().getExpectedUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 9, 5, 0, 123_400_000));
    }

    @Test
    void aSaveFromAnOutdatedCopyIs409WithItsText() throws Exception {
        final var outdated = "Your bio page changed in another tab or window. We loaded the latest version; check your changes and save again.";
        when(bioPageService.update(any(BioPageUpdateDto.class))).thenThrow(new BioPageConflictException(outdated));

        mockMvc.perform(signedIn(put(MY_PAGE)).content(page("[]")))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\":\"" + outdated + "\"}", true));
    }

    @Test
    void aTooLongNameIsAFieldError() throws Exception {
        mockMvc.perform(signedIn(put(MY_PAGE)).content(page("[]").replace("\"Ana Horvat\"", "\"" + "a".repeat(51) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.displayName").value("Name must be 50 characters or fewer"));

        verifyNoInteractions(bioPageService);
    }

    @Test
    void aTooLongBioIsAFieldError() throws Exception {
        mockMvc.perform(signedIn(put(MY_PAGE)).content(page("[]").replace("\"Photos from Zagreb\"", "\"" + "b".repeat(161) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.bio").value("Bio must be 160 characters or fewer"));

        verifyNoInteractions(bioPageService);
    }

    @Test
    void aBlankTitleIsAFieldError() throws Exception {
        mockMvc.perform(signedIn(put(MY_PAGE)).content(page("[" + button("\"  \"") + "]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['buttons[0].title']").value("Give the button a title"));
        mockMvc.perform(signedIn(put(MY_PAGE)).content(page("[" + button("\"" + "t".repeat(61) + "\"") + "]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['buttons[0].title']").value("Button title must be 60 characters or fewer"));

        verifyNoInteractions(bioPageService);
    }

    @Test
    void aButtonWithoutALinkIsAFieldError() throws Exception {
        mockMvc.perform(signedIn(put(MY_PAGE)).content(page("[{\"title\":\"Instagram\"}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['buttons[0].urlId']").value("Pick a link"));
        mockMvc.perform(signedIn(put(MY_PAGE)).content(page("[null]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['buttons[0]']").value("Pick a link"));

        verifyNoInteractions(bioPageService);
    }

    @Test
    void thirtyOneButtonsIsAFieldError() throws Exception {
        final var buttons = IntStream.rangeClosed(1, 31)
                .mapToObj(id -> "{\"urlId\":" + id + ",\"title\":\"Link " + id + "\"}")
                .collect(Collectors.joining(",", "[", "]"));

        mockMvc.perform(signedIn(put(MY_PAGE)).content(page(buttons)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.buttons").value("A page can have at most 30 buttons"));

        verifyNoInteractions(bioPageService);
    }

    @Test
    void missingChoicesAreFieldErrors() throws Exception {
        mockMvc.perform(signedIn(put(MY_PAGE)).content("{\"displayName\":\"Ana\",\"bio\":\"\",\"buttons\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.avatarKind").value("Choose an avatar"))
                .andExpect(jsonPath("$.accent").value("Choose an accent colour"))
                .andExpect(jsonPath("$.theme").value("Choose a theme"));

        verifyNoInteractions(bioPageService);
    }

    @Test
    void anUnknownIconIs400() throws Exception {
        mockMvc.perform(signedIn(put(MY_PAGE)).content(page("[{\"urlId\":41,\"title\":\"Myspace\",\"icon\":\"MYSPACE\"}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(allOf(
                        containsString("WEBSITE, EMAIL, INSTAGRAM, TIKTOK, YOUTUBE, X, FACEBOOK, LINKEDIN, GITHUB, SPOTIFY, WHATSAPP or TELEGRAM"),
                        containsString("icon"))));

        verifyNoInteractions(bioPageService);
    }

    private static String page(final String buttons) {
        return "{\"displayName\":\"Ana Horvat\",\"bio\":\"Photos from Zagreb\",\"avatarKind\":\"INITIALS\",\"accent\":\"TEAL\","
                + "\"theme\":\"AUTO\",\"indexable\":false,\"buttons\":" + buttons + "}";
    }

    private static String button(final String title) {
        return "{\"urlId\":41,\"title\":" + title + ",\"icon\":\"INSTAGRAM\",\"visible\":true}";
    }

    private static BioPageResponse ana() {
        final var createdAt = LocalDateTime.of(2026, 10, 26, 9, 0);
        return new BioPageResponse(12L, "ana", "Ana Horvat", "Photos from Zagreb", BioAvatarKind.EMOJI, "x", BioAccent.TEAL, BioTheme.AUTO,
                false, false, null, null,
                List.of(new BioPageResponse.Button(31L, 41L, "ana-ig", "instagram.com", "Instagram", BioIcon.INSTAGRAM, true, BioLinkState.LIVE, true)),
                createdAt, createdAt.plusMinutes(5));
    }

    private static PublicBioPageResponse publicAna() {
        return new PublicBioPageResponse("ana", "Ana Horvat", "Photos from Zagreb", BioAvatarKind.EMOJI, "x", BioAccent.TEAL, BioTheme.AUTO,
                false, List.of(new PublicBioPageResponse.Button("Instagram", BioIcon.INSTAGRAM, "ana-ig")));
    }

    private static MockHttpServletRequestBuilder signedIn(final MockHttpServletRequestBuilder request) {
        return request.header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + USER_TOKEN).contentType(MediaType.APPLICATION_JSON);
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
            AnyHostIsPrimary.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            ClickRequestResolver.class,
            AnalyticsController.class,
            BioPageController.class,
            BioRedirectController.class,
            PublicBioPageController.class
    })
    static class TestApplication {
    }

}
