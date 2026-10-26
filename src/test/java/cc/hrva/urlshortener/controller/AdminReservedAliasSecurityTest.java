package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.ReservedAliasResponse;
import cc.hrva.urlshortener.exception.ReservedAliasNotFoundException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.ReservedAliasService;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminReservedAliasSecurityTest.TestApplication.class,
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
class AdminReservedAliasSecurityTest {

    private static final String RESERVED_ALIASES = "/api/v1/admin/reserved-aliases";
    private static final String RESERVED_ALIAS = RESERVED_ALIASES + "/3";
    private static final String BODY = "{\"alias\":\"Promo\",\"reason\":\"Spring\"}";
    private static final String ADMIN = "admin@hrva.cc";
    private static final String USER = "user@hrva.cc";
    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservedAliasService reservedAliasService;

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
        signedIn(ADMIN_TOKEN, ADMIN, "ROLE_ADMIN");
        signedIn(USER_TOKEN, USER, "ROLE_USER");
    }

    @Test
    void anAdminListsTheReservedAliases() throws Exception {
        when(reservedAliasService.list()).thenReturn(List.of(
                new ReservedAliasResponse(null, "actuator", "Used by hrva.cc itself", true, null, null, List.of()),
                promo()));

        mockMvc.perform(asAdmin(get(RESERVED_ALIASES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].builtIn").value(true))
                .andExpect(jsonPath("$[1].alias").value("promo"))
                .andExpect(jsonPath("$[1].inUse[0].ownerEmail").value("ana@example.com"));
    }

    @Test
    void aSignedInUserWhoIsNotAnAdminIsForbidden() throws Exception {
        mockMvc.perform(signedInAs(USER_TOKEN, get(RESERVED_ALIASES)))
                .andExpect(status().isForbidden());
        mockMvc.perform(signedInAs(USER_TOKEN, post(RESERVED_ALIASES)).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(signedInAs(USER_TOKEN, delete(RESERVED_ALIAS)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(reservedAliasService);
    }

    @Test
    void nobodySignedInIsUnauthorized() throws Exception {
        mockMvc.perform(get(RESERVED_ALIASES))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(RESERVED_ALIASES).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(RESERVED_ALIAS))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reservedAliasService);
    }

    @Test
    void anAdminReservesAnAlias() throws Exception {
        when(reservedAliasService.reserve(new ReservedAliasDto("Promo", "Spring"), ADMIN)).thenReturn(promo());

        mockMvc.perform(asAdmin(post(RESERVED_ALIASES)).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.alias").value("promo"))
                .andExpect(jsonPath("$.builtIn").value(false));
    }

    @Test
    void anAdminRemovesAReservation() throws Exception {
        mockMvc.perform(asAdmin(delete(RESERVED_ALIAS)))
                .andExpect(status().isNoContent());

        verify(reservedAliasService).unreserve(3L, ADMIN);
    }

    @Test
    void invalidReservationsAreBadRequests() throws Exception {
        final var invalid = Map.of(
                "{\"alias\":\"bad alias!\",\"reason\":\"x\"}", Map.entry("$.alias", "Alias can only contain letters, numbers, hyphens and underscores"),
                "{\"alias\":\"" + "a".repeat(33) + "\",\"reason\":\"x\"}", Map.entry("$.alias", "Alias must be 32 characters or fewer"),
                "{\"alias\":\"\",\"reason\":\"x\"}", Map.entry("$.alias", "Alias is required"),
                "{\"alias\":\"promo\",\"reason\":\"\"}", Map.entry("$.reason", "Reason is required"),
                "{\"alias\":\"promo\",\"reason\":\"" + "r".repeat(201) + "\"}", Map.entry("$.reason", "Reason must be 200 characters or fewer"));

        for (final var reservation : invalid.entrySet()) {
            final var error = reservation.getValue();
            mockMvc.perform(asAdmin(post(RESERVED_ALIASES)).content(reservation.getKey()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath(error.getKey()).value(error.getValue()));
        }

        verifyNoInteractions(reservedAliasService);
    }

    @Test
    void aDuplicateIsAConflict() throws Exception {
        when(reservedAliasService.reserve(new ReservedAliasDto("Promo", "Spring"), ADMIN))
                .thenThrow(new ShortUrlAlreadyExistsException("This alias is already reserved."));

        mockMvc.perform(asAdmin(post(RESERVED_ALIASES)).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This alias is already reserved."));
    }

    @Test
    void removingAnUnknownEntryIsNotFound() throws Exception {
        doThrow(new ReservedAliasNotFoundException("Reserved alias not found")).when(reservedAliasService).unreserve(3L, ADMIN);

        mockMvc.perform(asAdmin(delete(RESERVED_ALIAS)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Reserved alias not found"));
    }

    private static ReservedAliasResponse promo() {
        return new ReservedAliasResponse(3L, "promo", "Spring", false, LocalDateTime.of(2026, 10, 25, 10, 0), ADMIN,
                List.of(new ReservedAliasResponse.InUseLink(41L, "Promo", "ana@example.com", true)));
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
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            AdminReservedAliasController.class
    })
    static class TestApplication {
    }

}
