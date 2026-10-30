package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.settings.PublicSettingsResponse;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = PublicSettingsEndpointSecurityTest.TestApplication.class,
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
class PublicSettingsEndpointSecurityTest {

    private static final String PUBLIC_SETTINGS = "/api/v1/public/settings";
    private static final RSAKey SIGNING_KEY = generateSigningKey();
    private static final PublicSettingsResponse SETTINGS = new PublicSettingsResponse(true, false, true, true, false, true, "1x00000000000000000000AA", 30,
            1000);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettingsService settingsService;

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

    @Test
    void anyoneCanReadThePublicSwitches() throws Exception {
        when(settingsService.publicSettings()).thenReturn(SETTINGS);

        mockMvc.perform(get(PUBLIC_SETTINGS))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=60, public"))
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.anonymousLinks").value(true))
                .andExpect(jsonPath("$.newSignups").value(false))
                .andExpect(jsonPath("$.customAliases").value(true))
                .andExpect(jsonPath("$.googleSignIn").value(true))
                .andExpect(jsonPath("$.githubSignIn").value(false))
                .andExpect(jsonPath("$.humanCheck").value(true))
                .andExpect(jsonPath("$.turnstileSiteKey").value("1x00000000000000000000AA"))
                .andExpect(jsonPath("$.anonymousExpiryDays").value(30))
                .andExpect(jsonPath("$.anonymousVisitLimit").value(1000));
    }

    @Test
    void theSiteKeyIsNullWhileTheHumanCheckIsOff() throws Exception {
        when(settingsService.publicSettings()).thenReturn(new PublicSettingsResponse(true, true, true, true, true, false, null, null, null));

        mockMvc.perform(get(PUBLIC_SETTINGS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.humanCheck").value(false))
                .andExpect(jsonPath("$.turnstileSiteKey").value(nullValue()))
                .andExpect(jsonPath("$.anonymousExpiryDays").value(nullValue()))
                .andExpect(jsonPath("$.anonymousVisitLimit").value(nullValue()));
    }

    @Test
    void aStaleTokenDoesNotBreakIt() throws Exception {
        when(settingsService.publicSettings()).thenReturn(SETTINGS);
        when(tokenProvider.validateToken("expired.jwt.token")).thenReturn(false);

        mockMvc.perform(get(PUBLIC_SETTINGS).header(HttpHeaders.AUTHORIZATION, "Bearer expired.jwt.token"))
                .andExpect(status().isOk());
    }

    @Test
    void onlyGetIsAnswered() throws Exception {
        mockMvc.perform(post(PUBLIC_SETTINGS))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"));
        mockMvc.perform(delete(PUBLIC_SETTINGS))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(settingsService);
    }

    @Test
    void nothingIsRateLimited() throws Exception {
        when(settingsService.publicSettings()).thenReturn(SETTINGS);

        mockMvc.perform(get(PUBLIC_SETTINGS))
                .andExpect(status().isOk());

        verifyNoInteractions(rateLimiter);
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
            PublicSettingsController.class
    })
    static class TestApplication {
    }

}
