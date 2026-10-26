package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.bio.BioHandleAvailabilityResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioPageButtonDto;
import cc.hrva.urlshortener.dto.bio.BioPageCreateDto;
import cc.hrva.urlshortener.dto.bio.BioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioPageUpdateDto;
import cc.hrva.urlshortener.dto.bio.PublicBioPageResponse;
import cc.hrva.urlshortener.exception.BioPageConflictException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
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
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.BioPageService;
import cc.hrva.urlshortener.service.PublicBioPageService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
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
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            BioPageController.class,
            BioRedirectController.class,
            PublicBioPageController.class
    })
    static class TestApplication {
    }

}
