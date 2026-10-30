package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.ClockConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.AppSetting;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.repository.AppSettingRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingDefaults;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AdminSettingsSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost",
                "app.anonymous-url-creation-enabled=true"
        })
@AutoConfigureMockMvc
class AdminSettingsSecurityTest {

    private static final String SETTINGS = "/api/v1/admin/settings";
    private static final String ANONYMOUS_LINKS = SETTINGS + "/ANONYMOUS_LINKS";
    private static final String OFF = "{\"value\":false}";
    private static final String ADMIN = "admin@hrva.cc";
    private static final String USER = "user@hrva.cc";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SettingsService settingsService;

    @MockitoBean
    private AppSettingRepository appSettingRepository;

    @MockitoBean
    private AuditLogService auditLogService;

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
        signedIn("admin-token", ADMIN, "ROLE_ADMIN");
        signedIn("user-token", USER, "ROLE_USER");
        settingsService.refresh();
        clearInvocations(appSettingRepository);
    }

    @Test
    void anAdminGetsEverySettingInCatalogueOrder() throws Exception {
        mockMvc.perform(signedIn(get(SETTINGS), "admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(SettingKey.values().length))
                .andExpect(jsonPath("$[0].key").value("ANONYMOUS_LINKS"))
                .andExpect(jsonPath("$[0].group").value("ACCESS"))
                .andExpect(jsonPath("$[0].type").value("SWITCH"))
                .andExpect(jsonPath("$[0].value").value(true))
                .andExpect(jsonPath("$[0].changed").value(false))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].unavailableReason").doesNotExist());
    }

    @Test
    void anAdminTurnsASwitchOffAndItIsAuditedOnce() throws Exception {
        final var rows = new ArrayList<AppSetting>();
        when(appSettingRepository.save(any(AppSetting.class))).thenAnswer(invocation -> {
            rows.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(appSettingRepository.findAll()).thenAnswer(invocation -> List.copyOf(rows));

        mockMvc.perform(signedIn(put(ANONYMOUS_LINKS), "admin-token").content(OFF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("ANONYMOUS_LINKS"))
                .andExpect(jsonPath("$.value").value(false))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.changedBy").value(ADMIN));

        verify(auditLogService).log(ADMIN, AuditAction.CHANGE_SETTING, "Setting", "ANONYMOUS_LINKS", "from: On, to: Off");
    }

    @Test
    void anAdminResetsASettingAndItIsAudited() throws Exception {
        when(appSettingRepository.findById("ANONYMOUS_LINKS"))
                .thenReturn(Optional.of(AppSetting.builder().settingKey("ANONYMOUS_LINKS").settingValue("false").changedBy(ADMIN)
                        .changedAt(LocalDateTime.of(2026, 10, 29, 8, 0)).build()));

        mockMvc.perform(signedIn(delete(ANONYMOUS_LINKS), "admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("ANONYMOUS_LINKS"));

        verify(auditLogService).log(eq(ADMIN), eq(AuditAction.RESET_SETTING), eq("Setting"), eq("ANONYMOUS_LINKS"), any());
    }

    @Test
    void aNumberOutOfRangeIsABadRequestAndChangesNothing() throws Exception {
        mockMvc.perform(signedIn(put(SETTINGS + "/CAMPAIGN_LINKS"), "admin-token").content("{\"value\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.value").value("Use a number from 2 to 50."));

        verifyNoInteractions(auditLogService);
        verify(appSettingRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"value\":null}"})
    void aSwitchWithoutAValueIsABadRequestAndChangesNothing(final String body) throws Exception {
        mockMvc.perform(signedIn(put(ANONYMOUS_LINKS), "admin-token").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.value").value("Choose on or off."));

        verifyNoInteractions(auditLogService);
        verify(appSettingRepository, never()).save(any());
    }

    @Test
    void aChangeWithoutABodyIsAMalformedRequest() throws Exception {
        mockMvc.perform(signedIn(put(ANONYMOUS_LINKS), "admin-token"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"Malformed request body\"}", JsonCompareMode.STRICT));

        verifyNoInteractions(auditLogService);
        verify(appSettingRepository, never()).save(any());
    }

    @Test
    void anUnknownKeyIsNotFound() throws Exception {
        mockMvc.perform(signedIn(put(SETTINGS + "/NOPE"), "admin-token").content(OFF))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("There is no setting NOPE."));
        mockMvc.perform(signedIn(delete(SETTINGS + "/NOPE"), "admin-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("There is no setting NOPE."));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void aSignedInUserWhoIsNotAnAdminIsForbidden() throws Exception {
        mockMvc.perform(signedIn(get(SETTINGS), "user-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(signedIn(put(ANONYMOUS_LINKS), "user-token").content(OFF))
                .andExpect(status().isForbidden());
        mockMvc.perform(signedIn(delete(ANONYMOUS_LINKS), "user-token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(auditLogService, appSettingRepository);
    }

    @Test
    void nobodySignedInIsUnauthorized() throws Exception {
        mockMvc.perform(get(SETTINGS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put(ANONYMOUS_LINKS).contentType(MediaType.APPLICATION_JSON).content(OFF))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(ANONYMOUS_LINKS))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(auditLogService, appSettingRepository);
    }

    private void signedIn(final String token, final String email, final String role) {
        lenient().when(tokenProvider.validateToken(token)).thenReturn(true);
        lenient().when(tokenProvider.getAuthentication(token))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(email, null, List.of(new SimpleGrantedAuthority(role))));
        lenient().when(userRepository.findByEmail(email)).thenReturn(Optional.of(User.builder().email(email).active(true).build()));
    }

    private static MockHttpServletRequestBuilder signedIn(final MockHttpServletRequestBuilder request, final String token) {
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
            ClockConfiguration.class,
            SettingDefaults.class,
            DefaultSettingsService.class,
            AdminSettingsController.class
    })
    static class TestApplication {
    }

}
