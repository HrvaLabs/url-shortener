package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.bio.AdminBioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioHandleRevokeDto;
import cc.hrva.urlshortener.dto.bio.BioPageReasonDto;
import cc.hrva.urlshortener.dto.bio.BioPageReviewEntryResponse;
import cc.hrva.urlshortener.dto.bio.BioPageReviewResolveDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.BioHandleUnavailableException;
import cc.hrva.urlshortener.exception.BioPageConflictException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioPageReviewAction;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.AdminBioPageService;
import cc.hrva.urlshortener.service.BioPageReviewService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminBioPageSecurityTest.TestApplication.class,
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
class AdminBioPageSecurityTest {

    private static final String BIO_PAGES = "/api/v1/admin/bio-pages";
    private static final String HIDE = BIO_PAGES + "/12/hide";
    private static final String UNHIDE = BIO_PAGES + "/12/unhide";
    private static final String HANDLE = BIO_PAGES + "/12/handle";
    private static final String REVOKE = BIO_PAGES + "/12/revoke-handle";
    private static final String REVIEWS = "/api/v1/admin/bio-page-reviews";
    private static final String RESOLVE = REVIEWS + "/12/resolve";
    private static final String REASON_BODY = "{\"reason\":\"Impersonation\"}";
    private static final String HANDLE_BODY = "{\"handle\":\"ana-horvat\"}";
    private static final String RESOLVE_BODY = "{\"action\":\"OK\"}";
    private static final String ADMIN = "admin@hrva.cc";
    private static final String USER = "user@hrva.cc";
    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminBioPageService adminBioPageService;

    @MockitoBean
    private BioPageReviewService bioPageReviewService;

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
        signedIn(ADMIN_TOKEN, ADMIN, "ROLE_ADMIN");
        signedIn(USER_TOKEN, USER, "ROLE_USER");
    }

    @Test
    void anAdminSearchesTheBioPages() throws Exception {
        when(adminBioPageService.search(eq("ana"), eq(true), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(ana()), invocation.getArgument(2), 1));

        mockMvc.perform(asAdmin(get(BIO_PAGES).param("search", "ana").param("hidden", "true").param("page", "0").param("size", "20")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"content":[{"id":12,"handle":"ana","displayName":"Ana Horvat","bio":"Photos from Zagreb","ownerId":7,
                        "ownerEmail":"ana@example.com","ownerActive":true,"hidden":false,"hiddenReason":null,"hiddenAt":null,"hiddenBy":null,
                        "handleRevokedReason":null,"buttons":3,"visibleButtons":2,"reviewOpen":true,"createdAt":"2026-10-26T09:00:00",
                        "updatedAt":"2026-10-26T09:05:00"}],"page":{"totalElements":1}}
                        """));

        verify(adminBioPageService).search("ana", true, PageRequest.of(0, 20));
    }

    @Test
    void theListDefaultsToEveryPageTwentyAtATime() throws Exception {
        when(adminBioPageService.search(isNull(), eq(false), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(asAdmin(get(BIO_PAGES)))
                .andExpect(status().isOk());

        verify(adminBioPageService).search(null, false, PageRequest.of(0, 20));
    }

    @Test
    void aHiddenFilterThatIsNotABooleanIs400() throws Exception {
        mockMvc.perform(asAdmin(get(BIO_PAGES).param("hidden", "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for hidden"));

        verifyNoInteractions(adminBioPageService);
    }

    @Test
    void anAdminHidesAPage() throws Exception {
        when(adminBioPageService.hide(12L, new BioPageReasonDto("Impersonation"))).thenReturn(ana());

        mockMvc.perform(asAdmin(post(HIDE)).content(REASON_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12));
    }

    @Test
    void anAdminUnhidesAPage() throws Exception {
        when(adminBioPageService.unhide(12L)).thenReturn(ana());

        mockMvc.perform(asAdmin(post(UNHIDE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handle").value("ana"));
    }

    @Test
    void anAdminChangesAHandle() throws Exception {
        when(adminBioPageService.changeHandle(12L, new BioHandleDto("ana-horvat"))).thenReturn(ana());

        mockMvc.perform(asAdmin(put(HANDLE)).content(HANDLE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12));
    }

    @Test
    void aTakenHandleIs409() throws Exception {
        when(adminBioPageService.changeHandle(12L, new BioHandleDto("bob")))
                .thenThrow(new BioHandleUnavailableException("This handle is taken. Try another."));

        mockMvc.perform(asAdmin(put(HANDLE)).content("{\"handle\":\"bob\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This handle is taken. Try another."));
    }

    @Test
    void anAdminRevokesAHandle() throws Exception {
        when(adminBioPageService.revokeHandle(12L, new BioHandleRevokeDto("Impersonation", null))).thenReturn(ana());

        mockMvc.perform(asAdmin(post(REVOKE)).content(REASON_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12));
    }

    @Test
    void theRevokeCarriesTheHandleTheAdminSaw() throws Exception {
        when(adminBioPageService.revokeHandle(12L, new BioHandleRevokeDto("Impersonation", "ana"))).thenReturn(ana());

        mockMvc.perform(asAdmin(post(REVOKE)).content("{\"reason\":\"Impersonation\",\"expectedHandle\":\"ana\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12));
    }

    @Test
    void aHandleThatChangedSinceTheListLoadedIs409() throws Exception {
        when(adminBioPageService.revokeHandle(12L, new BioHandleRevokeDto("Impersonation", "ana-old")))
                .thenThrow(new BioPageConflictException("This handle changed. Reload and try again."));

        mockMvc.perform(asAdmin(post(REVOKE)).content("{\"reason\":\"Impersonation\",\"expectedHandle\":\"ana-old\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This handle changed. Reload and try again."));
    }

    @Test
    void revokingAPageWithoutAHandleIs400() throws Exception {
        when(adminBioPageService.revokeHandle(12L, new BioHandleRevokeDto("Impersonation", null))).thenThrow(new ApiException("This page has no handle."));

        mockMvc.perform(asAdmin(post(REVOKE)).content(REASON_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This page has no handle."));
    }

    @Test
    void anAdminListsTheOpenBioPageReviews() throws Exception {
        when(bioPageReviewService.openEntries(any(Pageable.class))).thenAnswer(invocation -> new PageImpl<>(List.of(
                new BioPageReviewEntryResponse(3L, 12L, "ana", "Ana Horvat", 7L, "ana@example.com", false, List.of("name imitates paypal"),
                        LocalDateTime.of(2026, 10, 26, 9, 0))), invocation.getArgument(0), 1));

        mockMvc.perform(asAdmin(get(REVIEWS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reviewId").value(3))
                .andExpect(jsonPath("$.content[0].pageId").value(12))
                .andExpect(jsonPath("$.content[0].reasons[0]").value("name imitates paypal"))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        verify(bioPageReviewService).openEntries(PageRequest.of(0, 20));
    }

    @Test
    void anAdminResolvesABioPageReview() throws Exception {
        mockMvc.perform(asAdmin(post(RESOLVE)).content("{\"action\":\"HIDE\",\"reason\":\"Phishing or scam\"}"))
                .andExpect(status().isNoContent());

        verify(adminBioPageService).resolveReview(12L, new BioPageReviewResolveDto(BioPageReviewAction.HIDE, "Phishing or scam"));
    }

    @Test
    void resolvingAPageNoLongerInTheQueueIs400() throws Exception {
        doThrow(new ApiException("This bio page is no longer in the review queue."))
                .when(adminBioPageService).resolveReview(12L, new BioPageReviewResolveDto(BioPageReviewAction.OK, null));

        mockMvc.perform(asAdmin(post(RESOLVE)).content(RESOLVE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("This bio page is no longer in the review queue."));
    }

    @Test
    void aSignedInUserWhoIsNotAnAdminIsForbidden() throws Exception {
        for (final var request : everyEndpoint()) {
            mockMvc.perform(signedInAs(USER_TOKEN, request))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(adminBioPageService, bioPageReviewService);
    }

    @Test
    void nobodySignedInIsUnauthorized() throws Exception {
        for (final var request : everyEndpoint()) {
            mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }

        verifyNoInteractions(adminBioPageService, bioPageReviewService);
    }

    @Test
    void aBlankReasonIsAFieldError() throws Exception {
        for (final var body : List.of("{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{}")) {
            for (final var path : List.of(HIDE, REVOKE)) {
                mockMvc.perform(asAdmin(post(path)).content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.reason").value("Reason is required"));
            }
        }

        verifyNoInteractions(adminBioPageService);
    }

    @Test
    void aLongReasonIsAFieldError() throws Exception {
        final var body = "{\"reason\":\"" + "r".repeat(201) + "\"}";
        for (final var path : List.of(HIDE, REVOKE)) {
            mockMvc.perform(asAdmin(post(path)).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("Reason must be 200 characters or fewer"));
        }
        mockMvc.perform(asAdmin(post(RESOLVE)).content("{\"action\":\"HIDE\",\"reason\":\"" + "r".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("Reason must be 200 characters or fewer"));

        verifyNoInteractions(adminBioPageService);
    }

    @Test
    void aReasonOfExactly200IsAccepted() throws Exception {
        final var reason = "r".repeat(200);
        when(adminBioPageService.hide(12L, new BioPageReasonDto(reason))).thenReturn(ana());

        mockMvc.perform(asAdmin(post(HIDE)).content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void anUnknownPageIs404() throws Exception {
        final var notFound = new BioPageNotFoundException("Bio page not found");
        when(adminBioPageService.hide(anyLong(), any())).thenThrow(notFound);
        when(adminBioPageService.unhide(anyLong())).thenThrow(notFound);
        when(adminBioPageService.changeHandle(anyLong(), any())).thenThrow(notFound);
        when(adminBioPageService.revokeHandle(anyLong(), any())).thenThrow(notFound);
        doThrow(notFound).when(adminBioPageService).resolveReview(anyLong(), any());

        for (final var request : List.of(
                post(HIDE).content(REASON_BODY), post(UNHIDE), put(HANDLE).content(HANDLE_BODY), post(REVOKE).content(REASON_BODY),
                post(RESOLVE).content(RESOLVE_BODY))) {
            mockMvc.perform(asAdmin(request))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Bio page not found"));
        }
    }

    @Test
    void anUnknownActionIs400() throws Exception {
        mockMvc.perform(asAdmin(post(RESOLVE)).content("{\"action\":\"BLOCK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Action must be OK or HIDE"));

        verifyNoInteractions(adminBioPageService);
    }

    @Test
    void aMissingActionIsAFieldError() throws Exception {
        mockMvc.perform(asAdmin(post(RESOLVE)).content("{\"reason\":\"Spam\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.action").value("Action is required"));

        verifyNoInteractions(adminBioPageService);
    }

    @Test
    void aPageIdThatIsNotANumberIs400() throws Exception {
        mockMvc.perform(asAdmin(post(BIO_PAGES + "/abc/unhide")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminBioPageService);
    }

    private static List<MockHttpServletRequestBuilder> everyEndpoint() {
        return List.of(
                get(BIO_PAGES),
                post(HIDE).content(REASON_BODY),
                post(UNHIDE),
                put(HANDLE).content(HANDLE_BODY),
                post(REVOKE).content(REASON_BODY),
                get(REVIEWS),
                post(RESOLVE).content(RESOLVE_BODY));
    }

    private static AdminBioPageResponse ana() {
        return new AdminBioPageResponse(12L, "ana", "Ana Horvat", "Photos from Zagreb", 7L, "ana@example.com", true, false, null, null, null,
                null, 3L, 2L, true, LocalDateTime.of(2026, 10, 26, 9, 0), LocalDateTime.of(2026, 10, 26, 9, 5));
    }

    private void signedIn(final String token, final String email, final String role) {
        lenient().when(tokenProvider.validateToken(token)).thenReturn(true);
        lenient().when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(User.builder().email(email).active(true).build()));
    }

    private static MockHttpServletRequestBuilder asAdmin(final MockHttpServletRequestBuilder request) {
        return signedInAs(ADMIN_TOKEN, request);
    }

    private static MockHttpServletRequestBuilder signedInAs(final String token, final MockHttpServletRequestBuilder request) {
        return request.header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
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
            AdminBioPageController.class,
            AdminBioPageReviewController.class
    })
    static class TestApplication {
    }

}
