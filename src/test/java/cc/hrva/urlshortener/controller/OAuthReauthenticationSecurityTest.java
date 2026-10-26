package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.security.RecentSignIn;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.LoginAttemptService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.impl.DefaultPasskeyService;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonies;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremony;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyPurpose;
import cc.hrva.urlshortener.service.passkey.PasskeyCeremonyStore;
import cc.hrva.urlshortener.service.passkey.PasskeyJson;
import cc.hrva.urlshortener.service.passkey.PasskeyOptionsFactory;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import cc.hrva.urlshortener.validator.impl.DefaultAuthValidator;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = OAuthReauthenticationSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=" + OAuthReauthenticationSecurityTest.JWT_SECRET,
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class OAuthReauthenticationSecurityTest {

    static final String JWT_SECRET =
            "c2lnbi1pbi1lbmRwb2ludC1zZWN1cml0eS10ZXN0LW9ubHktand0LXNlY3JldC1mb3ItdGhlLXJlYWwtdG9rZW4tcHJvdmlkZXI=";
    static final Instant NOW = Instant.parse("2026-10-25T14:00:00Z");

    private static final String REGISTRATION_OPTIONS = "/api/v1/users/me/passkeys/registration/options";
    private static final String GOOGLE_EMAIL = "google@example.com";
    private static final String CEREMONY_ID = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n";
    private static final String SIGN_IN_AGAIN = "For your security, sign in again to change your passkeys and security keys.";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @MockitoBean
    private PasskeyCeremonyStore passkeyCeremonyStore;

    @MockitoBean
    private WebAuthnRelyingPartyOperations relyingPartyOperations;

    @MockitoBean
    private AccountLock accountLock;

    @MockitoBean
    private LoginAttemptService loginAttemptService;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @Test
    void aGoogleAccountAddsAPasskeyOnlyWithinFifteenMinutesOfSigningIn() throws Exception {
        final var google = User.builder()
                .id(42L)
                .email(GOOGLE_EMAIL)
                .authProvider("google")
                .active(true)
                .webauthnUserHandle("Xk3vUserHandleOfTheGoogleAccount43Character")
                .build();
        when(userRepository.findByEmail(GOOGLE_EMAIL)).thenReturn(Optional.of(google));
        when(userService.getUserFromToken()).thenReturn(google);
        final var ceremony = ArgumentCaptor.forClass(PasskeyCeremony.class);
        when(passkeyCeremonyStore.save(ceremony.capture())).thenReturn(CEREMONY_ID);

        mockMvc.perform(post(REGISTRATION_OPTIONS).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + tokenIssuedMinutesAgo(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ceremonyId").value(CEREMONY_ID))
                .andExpect(jsonPath("$.options.user.name").value(GOOGLE_EMAIL));

        mockMvc.perform(post(REGISTRATION_OPTIONS).header(JwtFilter.AUTHORIZATION_HEADER, "Bearer " + tokenIssuedMinutesAgo(16)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {"error":"%s"}""".formatted(SIGN_IN_AGAIN), JsonCompareMode.STRICT));

        verify(passkeyCeremonyStore, times(1)).save(ceremony.getValue());
        assertThat(ceremony.getValue().purpose()).isEqualTo(PasskeyCeremonyPurpose.REGISTRATION);
        assertThat(ceremony.getValue().userId()).isEqualTo(42L);
        verifyNoInteractions(loginAttemptService);
    }

    private static String tokenIssuedMinutesAgo(final long minutes) {
        return Jwts.builder()
                .subject(GOOGLE_EMAIL)
                .claim("auth", "ROLE_USER")
                .issuedAt(Date.from(NOW.minus(Duration.ofMinutes(minutes))))
                .expiration(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET)), Jwts.SIG.HS512)
                .compact();
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
            PasskeyController.class,
            DefaultPasskeyService.class,
            TokenProvider.class,
            RecentSignIn.class,
            Reauthentication.class,
            DefaultAuthValidator.class,
            PasskeyCeremonies.class,
            PasskeyOptionsFactory.class,
            PasskeyJson.class
    })
    static class TestApplication {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

    }

}
